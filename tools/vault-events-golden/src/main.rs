//! Golden test vectors for VaultHome's Kotlin port of `vault-events`.
//! Built against VaultSearch `crates/vault-events` (PR #9). Prints JSON on stdout.
use base64::Engine;
use serde_json::{json, Value};
use vault_events::conflict::{self, Decision};
use vault_events::event::{cause, sha256_hex, ConflictInfo, NoteEvent, Stamp};
use vault_events::key;
use vault_events::net::IndexReply;
use vault_events::vault::is_ignored;

fn b64(b: &[u8]) -> String { base64::engine::general_purpose::STANDARD.encode(b) }
fn stamp_json(s: &Stamp) -> Value { serde_json::to_value(s).unwrap() }
fn decision_json(d: &Decision) -> Value {
    match d {
        Decision::Ignore => json!({"kind":"ignore"}),
        Decision::Adopt => json!({"kind":"adopt"}),
        Decision::Apply { save_local_as } => json!({"kind":"apply","save_local_as":save_local_as}),
        Decision::KeepLocal { local, save_incoming_as } => json!({"kind":"keep_local","local":stamp_json(local),"save_incoming_as":save_incoming_as}),
    }
}

fn main() {
    let day: u64 = 1_763_424_000_000; // 2025-11-18T00:00:00Z
    // --- keys
    let paths = ["Notes/Vault Event Sync Plan.md", "Attachments /img 1.png", "Daily/2026-11-18.md",
        "Notes/C# and $HOME ?*%.md", "Notes/Zürich ✓.md", "a", "Notes/A #1.md", "Notes/100% done.md",
        "Notes/quote \" and tab\t.md", "Notes/emoji 🎿.md",
        "../x.md", "/etc/passwd", "a//b", "a/./b", "a\\b", "", "a/", "nul\u{0}x"];
    let keys: Vec<Value> = paths.iter().map(|p| match key::path_to_key(p) {
        Ok(k) => json!({"path":p,"key":k,"back":key::key_to_path(&k).ok()}),
        Err(e) => json!({"path":p,"error":format!("{e:?}")}),
    }).collect();
    let bad = ["vault/notes/..%2Fx","vault/notes/a/%2E%2E/b","vault/other/x","vault/notes/x%2","vault/notes/a%2Fb",
        "vault/notes/%61","vault/notes/a%23","vault/notes/","vault/notes","vault/notes/a%zz","vault/notes/%C3%BC"];
    let key_decode: Vec<Value> = bad.iter().map(|k| match key::key_to_path(k) {
        Ok(p) => json!({"key":k,"path":p}),
        Err(e) => json!({"key":k,"error":format!("{e:?}")}),
    }).collect();
    // --- events
    let mut events = Vec::new();
    let mut add = |name: &str, e: NoteEvent| {
        let enc = String::from_utf8(e.encode()).unwrap();
        events.push(json!({"name":name,"key":e.key().unwrap(),"encoded":enc,
            "path":e.stamp.path,"version":e.stamp.version,"ts_ms":e.stamp.ts_ms,"origin":e.stamp.origin,
            "deleted":e.stamp.deleted,"sha256":e.stamp.sha256,"prev_sha256":e.stamp.prev_sha256,
            "content_b64":e.content.as_ref().map(|c| b64(c))}));
    };
    add("put_simple", NoteEvent::put("a.md", b"hi".to_vec(), "box", 1, None, 7));
    add("put_prev", NoteEvent::put("Notes/a.md", b"hello\n".to_vec(), "box", 3, Some(sha256_hex(b"x")), 5));
    add("delete", NoteEvent::delete("Notes/a.md", "s24", 4, Some(sha256_hex(b"hello\n")), 6));
    add("delete_no_prev", NoteEvent::delete("Notes/gone.md", "latitude", 1, None, day));
    add("binary", NoteEvent::put("Attachments /x.png", vec![0, 255, 10, 13, 0x7f, 0x80], "box", 1, None, 1));
    add("unicode_path", NoteEvent::put("Notes/Zürich ✓ 🎿.md", "Grüezi — ✓\n".as_bytes().to_vec(), "s24", 12, Some(sha256_hex(b"old")), 1_763_481_234_567));
    add("escape_path", NoteEvent::put("Notes/C# and $HOME ?*%.md", b"# t\n".to_vec(), "nitro", 2, None, day + 1));
    add("json_escapes", NoteEvent::put("Notes/q \" x\t\u{1}\u{7f}.md", b"\x00\x01".to_vec(), "dev \"q\" \\ /", 9, None, 18_446_744_073_709_551_615u64 / 2));
    add("empty", NoteEvent::put("Notes/empty.md", Vec::new(), "box", 1, None, 0));
    add("big_numbers", NoteEvent::put("b.md", b"b".to_vec(), "box", u64::MAX / 4, None, u64::MAX / 8));
    // --- writer / cause / conflict (optional fields)
    let mut labelled = Vec::new();
    let mut addl = |name: &str, e: NoteEvent| {
        labelled.push(json!({"name":name,"key":e.key().unwrap(),"encoded":String::from_utf8(e.encode()).unwrap(),
            "path":e.stamp.path,"version":e.stamp.version,"ts_ms":e.stamp.ts_ms,"origin":e.stamp.origin,
            "deleted":e.stamp.deleted,"prev_sha256":e.stamp.prev_sha256,"writer":e.stamp.writer,"cause":e.stamp.cause,
            "content_b64":e.content.as_ref().map(|c| b64(c)),"conflict":e.conflict}));
    };
    addl("put_writer", NoteEvent::put("a.md", b"hi".to_vec(), "box", 1, None, 7).by("box/galanonim", cause::LOCAL_EDIT));
    addl("delete_writer", NoteEvent::delete("Notes/a.md", "s24", 4, Some(sha256_hex(b"hello\n")), 6).by("s24/external", cause::DELETE));
    addl("escaped_writer", NoteEvent::put("b.md", b"b".to_vec(), "x", 2, None, 1).by("x/\"q\" \u{1}", "bisync-pull"));
    let mut cc = NoteEvent::put("Daily/p (conflict nitro5 2026-11-18).jpg", vec![1, 2, 3], "s24", 1, None, day)
        .by("s24/vaulthome", cause::CONFLICT_COPY);
    cc.conflict = Some(ConflictInfo { of: "Daily/p.jpg".into(), winner_sha256: sha256_hex(b"w"), winner_size: 1,
        winner_origin: "s24".into(), loser_sha256: sha256_hex(&[1, 2, 3]), loser_size: 3, loser_origin: "nitro5".into() });
    addl("conflict_copy", cc);
    // --- decode rejects (payload strings)
    let good = String::from_utf8(NoteEvent::put("a.md", b"hi".to_vec(), "box", 1, None, 7).encode()).unwrap();
    let mut rejects = Vec::new();
    let mut variants: Vec<(String, Option<&str>)> = Vec::new();
    let mut v: Value = serde_json::from_str(&good).unwrap();
    v["format"] = 2.into(); variants.push((v.to_string(), None));
    v["format"] = 0.into(); variants.push((v.to_string(), None));
    let mut v: Value = serde_json::from_str(&good).unwrap(); v["content"] = "aGo=".into(); variants.push((v.to_string(), None));
    variants.push((good.clone(), Some("vault/notes/b.md")));
    let mut v: Value = serde_json::from_str(&good).unwrap(); v["path"] = "../x".into(); variants.push((v.to_string(), None));
    let mut v: Value = serde_json::from_str(&good).unwrap(); v["deleted"] = true.into(); variants.push((v.to_string(), None));
    let mut v: Value = serde_json::from_str(&good).unwrap(); v.as_object_mut().unwrap().remove("content"); variants.push((v.to_string(), None));
    let mut v: Value = serde_json::from_str(&good).unwrap(); v["content"] = "!!".into(); variants.push((v.to_string(), None));
    variants.push(("not json".into(), None));
    variants.push(("{\"format\":1}".into(), None));
    // accepted variants: minimal tombstone (defaults), null prev, unknown field, key match
    variants.push(("{\"format\":1,\"path\":\"t.md\",\"version\":2,\"ts_ms\":3,\"origin\":\"x\",\"deleted\":true}".into(), Some("vault/notes/t.md")));
    let mut v: Value = serde_json::from_str(&good).unwrap(); v["prev_sha256"] = Value::Null; v["extra"] = 5.into(); variants.push((v.to_string(), Some("vault/notes/a.md")));
    let mut v: Value = serde_json::from_str(&good).unwrap(); v["content"] = Value::Null; v["deleted"] = true.into(); v["sha256"] = "".into(); variants.push((v.to_string(), None));
    for (payload, k) in variants {
        let r = NoteEvent::decode(payload.as_bytes(), k);
        rejects.push(match r {
            Ok(ev) => json!({"payload":payload,"key":k,"ok":true,"reencoded":String::from_utf8(ev.encode()).unwrap()}),
            Err(e) => json!({"payload":payload,"key":k,"ok":false,"error":format!("{e:?}").split(['(', ' ', '{']).next().unwrap().to_string()}),
        });
    }
    // --- ordering
    let st = |c: &str, o: &str, v: u64, ts: u64, del: bool| -> Stamp {
        if del { NoteEvent::delete("a.md", o, v, None, ts).stamp } else { NoteEvent::put("a.md", c.as_bytes().to_vec(), o, v, None, ts).stamp }
    };
    let pool = vec![st("a","box",2,100,false), st("b","s24",2,100,false), st("c","box",3,1,false), st("","zzz",2,100,true),
        st("a","aaa",2,101,false), st("z","box",2,100,false), st("a","box",2,100,true), st("a","Box",2,100,false)];
    let mut beats = Vec::new();
    for a in &pool { for b in &pool { beats.push(json!({"a":stamp_json(a),"b":stamp_json(b),"beats":a.beats(b),"same":a.same_state(b)})); } }
    // --- dates and names
    let dates: Vec<Value> = [0u64, day, 951_782_400_000, 1_763_481_234_567, 86_399_999, 4_102_444_800_000, 253_402_300_799_000]
        .iter().map(|t| json!({"ts_ms":t,"date":conflict::utc_date(*t)})).collect();
    let names: Vec<Value> = [("Notes/a.md","s24",day),("README","a/b",day),(".hidden","x",day),("Notes/x.tar.gz","box",0),
        ("Notes/Zürich.md","gerät ✓",day),("a.","",day),("dir.d/file","dev_1.2-x",day),("Notes/🎿.md","🎿",day)]
        .iter().map(|(p,o,t)| json!({"path":p,"origin":o,"ts_ms":t,"name":conflict::conflict_name_for(p,o,*t),"is_copy":conflict::is_conflict_copy(&conflict::conflict_name_for(p,o,*t))})).collect();
    // --- decide matrix
    let h = |s: &str| sha256_hex(s.as_bytes());
    let p = |c: &str, o: &str, v: u64, prev: Option<&str>, ts: u64| NoteEvent::put("Notes/a.md", c.as_bytes().to_vec(), o, v, prev.map(h), ts).stamp;
    let d = |o: &str, v: u64, prev: Option<&str>, ts: u64| NoteEvent::delete("Notes/a.md", o, v, prev.map(h), ts).stamp;
    let states: Vec<Option<Stamp>> = vec![None, Some(p("v1","box",1,None,day)), Some(p("v2","s24",2,Some("v1"),day+5)), Some(d("box",2,Some("v1"),day))];
    let disks: Vec<Option<String>> = vec![None, Some(h("v1")), Some(h("v2")), Some(h("mine")), Some(h("theirs"))];
    let incomings = vec![p("v1","box",1,None,day), p("v2","s24",2,Some("v1"),day+5), p("theirs","s24",2,Some("v1"),day-5),
        p("theirs","s24",2,Some("v1"),day+10), p("theirs","aaa",3,Some("v2"),day), p("theirs","zzz",2,None,day),
        d("s24",2,Some("v1"),day), d("s24",3,Some("v2"),day+20), d("aaa",1,None,day), p("mine","s24",2,Some("v1"),day)];
    let mut decide = Vec::new();
    for s in &states { for dk in &disks { for inc in &incomings { for lt in [day, day+7, day+100] {
        let r = conflict::decide(s.as_ref(), dk.as_deref(), inc, "box", lt);
        decide.push(json!({"state":s.as_ref().map(stamp_json),"disk":dk,"incoming":stamp_json(inc),"me":"box","local_ts_ms":lt,"decision":decision_json(&r)}));
    }}}}
    // --- ignore rules
    let ign: Vec<Value> = [".obsidian/workspace.json","Notes/.trash/x.md","Notes/.vault-events-tmp-1-2","Notes/a.md.tmp","Notes/a.md~",
        "Notes/a.md","Attachments /p.png","Notes/note4.md..conflict-Date1","~$doc.docx","Notes/.#lock","x.swp","x.swx","x.part","x.crdownload",
        "x.partial","Notes/a (conflict s24 2025-11-18).md","Notes/x.TMP",
        "Daily/2026-11-19.md..conflict-Date1","Notes/x.md.conflict-2026-11-19","Notes/x.md.conflict1","Notes/a.conflict","Notes/a.conflictX",
        "Notes/conflict-resolution.md","Daily/2026-09-19-20260919_153121 (conflict nitro5 2026-11-18).jpg"]
        .iter().map(|p| json!({"path":p,"ignored":is_ignored(p)})).collect();
    // --- index reply
    let idx = IndexReply { format: 1, hub: "box".into(), stamps: vec![p("v1","box",1,None,day), d("s24",3,Some("v2"),day+20)] };
    let out = json!({
        "source":"VaultSearch crates/vault-events (feat/vault-events-stage1)",
        "keys":keys,"key_decode":key_decode,"events":events,"labelled_events":labelled,"decode":rejects,"beats":beats,
        "dates":dates,"conflict_names":names,"decide":decide,"ignored":ign,
        "index_reply":serde_json::to_string(&idx).unwrap(),
    });
    println!("{}", serde_json::to_string_pretty(&out).unwrap());
}
