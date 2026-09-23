// FT8/FT4 decoder CLI — streams JSON Lines to stdout as each decode lands.
// Usage: mfsk-decode [--ft4] [-c MY_CALL] [-x DX_CALL] [--freq-min HZ] [--freq-max HZ] [--sic-rounds N] [-k CALL ...] <wav_file>
// Output: {"freq":1234.0,"snr":-4.0,"dt":0.10,"msg":"JI1ORE K2UPD RR73"}

use std::collections::HashSet;
use std::env;
use std::io::{self, Write};
use std::sync::Mutex;

use mfsk_core::ft4::Ft4;
use mfsk_core::ft8::Ft8;
use mfsk_core::ft8::decode::DecodeResult;
use mfsk_core::msg::decode_request::DecodeRequest;
use mfsk_core::msg::hash_table::CallsignHashTable;
use mfsk_core::ProtocolId;

fn main() {
    let args: Vec<String> = env::args().collect();

    let mut wav_path: Option<String> = None;
    let mut my_call: Option<String> = None;
    let mut dx_call: Option<String> = None;
    let mut freq_min: f32 = 100.0;
    let mut freq_max: f32 = 3000.0;
    let mut sic_rounds: u32 = 3;
    let mut known_calls: Vec<String> = Vec::new();
    let mut is_ft4 = false;

    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "--ft4" => { is_ft4 = true; i += 1; }
            "-c" | "--my-call" => { my_call = args.get(i + 1).cloned(); i += 2; }
            "-x" | "--dx-call" => { dx_call = args.get(i + 1).cloned(); i += 2; }
            "--freq-min" => { freq_min = args.get(i + 1).and_then(|s| s.parse().ok()).unwrap_or(100.0); i += 2; }
            "--freq-max" => { freq_max = args.get(i + 1).and_then(|s| s.parse().ok()).unwrap_or(3000.0); i += 2; }
            "--sic-rounds" => { sic_rounds = args.get(i + 1).and_then(|s| s.parse().ok()).unwrap_or(3); i += 2; }
            "-k" | "--known-call" => { if let Some(v) = args.get(i + 1) { known_calls.push(v.clone()); } i += 2; }
            arg if !arg.starts_with('-') => { wav_path = Some(arg.to_string()); i += 1; }
            _ => { i += 1; }
        }
    }

    let wav_path = match wav_path {
        Some(p) => p,
        None => {
            eprintln!(
                "Usage: mfsk-decode [--ft4] [-c MY_CALL] [-x DX_CALL] [--freq-min HZ] [--freq-max HZ] [--sic-rounds N] [-k CALL ...] <wav>"
            );
            std::process::exit(1);
        }
    };

    let audio = match load_wav_12khz(&wav_path) {
        Ok(a) => a,
        Err(e) => {
            eprintln!("[mfsk-decode] WAV load error: {}", e);
            std::process::exit(1);
        }
    };

    let mut hash_table = CallsignHashTable::new();
    if let Some(ref c) = my_call { hash_table.insert(c.as_str()); }
    if let Some(ref c) = dx_call { hash_table.insert(c.as_str()); }
    for c in &known_calls { hash_table.insert(c.as_str()); }

    let stdout = io::stdout();
    let seen: Mutex<HashSet<Vec<u8>>> = Mutex::new(HashSet::new());

    let protocol_id = if is_ft4 { ProtocolId::Ft4 } else { ProtocolId::Ft8 };

    let on_result = |r: &DecodeResult| {
        let key = r.message77().to_vec();
        { let mut s = seen.lock().unwrap(); if !s.insert(key) { return; } }
        if let Some(decoded) = r.to_decoded(protocol_id, Some(&hash_table)) {
            let escaped = decoded.text
                .replace('\\', "\\\\").replace('"', "\\\"")
                .replace('\n', "\\n").replace('\r', "\\r");
            let line = format!(
                "{{\"freq\":{:.1},\"snr\":{:.1},\"dt\":{:.2},\"msg\":\"{}\"}}",
                decoded.freq_hz, decoded.snr_db, decoded.dt_sec, escaped,
            );
            let mut out = stdout.lock();
            writeln!(out, "{}", line).ok();
            let _ = out.flush();
        }
    };

    if is_ft4 {
        let _outcome = DecodeRequest::<Ft4>::new(&audio, freq_min, freq_max, 1.5, 150)
            .sic_rounds(sic_rounds as usize)
            .on_result(&on_result)
            .decode();
    } else {
        let _outcome = DecodeRequest::<Ft8>::new(&audio, freq_min, freq_max, 1.5, 150)
            .sic_rounds(sic_rounds as usize)
            .on_result(&on_result)
            .decode();
    }
}

fn load_wav_12khz(path: &str) -> Result<Vec<i16>, Box<dyn std::error::Error>> {
    let mut reader = hound::WavReader::open(path)?;
    let spec = reader.spec();
    if spec.channels != 1 {
        return Err(format!("mono expected, got {} ch", spec.channels).into());
    }
    if spec.sample_rate != 12000 {
        eprintln!("[mfsk-decode] warning: {} Hz (12000 expected)", spec.sample_rate);
    }
    let samples: Vec<i16> = match (spec.sample_format, spec.bits_per_sample) {
        (hound::SampleFormat::Int, 16) => {
            reader.samples::<i16>().map(|s| s.unwrap_or(0)).collect()
        }
        (hound::SampleFormat::Float, _) => reader
            .samples::<f32>()
            .map(|s| (s.unwrap_or(0.0).clamp(-1.0, 1.0) * 32767.0) as i16)
            .collect(),
        (hound::SampleFormat::Int, b) => {
            let scale = (1i64 << (b - 1)) as f64;
            reader
                .samples::<i32>()
                .map(|s| ((s.unwrap_or(0) as f64 / scale) * 32767.0) as i16)
                .collect()
        }
    };
    Ok(samples)
}
