use std::collections::HashSet;
use std::env;
use std::io::{self, Write};
use mfsk_core::ft8::Ft8;
use mfsk_core::ft4::Ft4;
use mfsk_core::decoder::{DecodeParams, Decoder, Depth, SlotInput};
fn main() {
    let args: Vec<String> = env::args().collect();
    let mut wav_path: Option<String> = None;
    let mut freq_min: f32 = 100.0;
    let mut freq_max: f32 = 3000.0;
    let mut is_ft4 = false;
    let mut depth: u8 = 1;
    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "-c"|"--my-call" => { i+=2; }
            "-x"|"--dx-call" => { i+=2; }
            "--freq-min" => { freq_min = args.get(i+1).and_then(|s|s.parse().ok()).unwrap_or(100.0); i+=2; }
            "--freq-max" => { freq_max = args.get(i+1).and_then(|s|s.parse().ok()).unwrap_or(3000.0); i+=2; }
            "--depth"|"--sic-rounds" => { depth = args.get(i+1).and_then(|s|s.parse().ok()).unwrap_or(1); i+=2; }
            "-k"|"--known-call" => { i+=2; }
            "--ft4" => { is_ft4 = true; i+=1; }
            arg if !arg.starts_with('-') => { wav_path = Some(arg.to_string()); i+=1; }
            _ => { i+=1; }
        }
    }
    let wav_path = match wav_path {
        Some(p) => p,
        None => { eprintln!("Usage: mfsk-decode [--ft4] [--freq-min HZ] [--freq-max HZ] [--depth 1|2|3] <wav>"); std::process::exit(1); }
    };
    let audio = match load_wav_12khz(&wav_path) {
        Ok(a) => a,
        Err(e) => { eprintln!("[mfsk-decode] WAV load error: {}", e); std::process::exit(1); }
    };
    let d = match depth { 1 => Depth::Fast, 3 => Depth::Deep, _ => Depth::Normal };
    let slot = SlotInput::i16(&audio);
    let stdout = io::stdout();
    let mut seen: HashSet<String> = HashSet::new();
    if is_ft4 {
        let mut dec = Decoder::<Ft4>::new(DecodeParams::for_band((freq_min, freq_max)).depth(d));
        let result = dec.decode(&slot);
        for row in &result.rows {
            let decoded = &row.decoded;
            let text = decoded.text.clone();
            if !seen.insert(text.clone()) { continue; }
            let escaped = text.replace('\\', "\\\\").replace('"', "\\\"").replace('\n', "\\n").replace('\r', "\\r");
            let line = format!("{{\"freq\":{:.1},\"snr\":{:.1},\"dt\":{:.2},\"msg\":\"{}\"}}",
                decoded.freq_hz, decoded.snr_db, decoded.dt_sec, escaped);
            let mut out = stdout.lock(); writeln!(out, "{}", line).ok(); let _ = out.flush();
        }
    } else {
        let mut dec = Decoder::<Ft8>::new(DecodeParams::for_band((freq_min, freq_max)).depth(d));
        let result = dec.decode(&slot);
        for row in &result.rows {
            let decoded = &row.decoded;
            let text = decoded.text.clone();
            if !seen.insert(text.clone()) { continue; }
            let escaped = text.replace('\\', "\\\\").replace('"', "\\\"").replace('\n', "\\n").replace('\r', "\\r");
            let line = format!("{{\"freq\":{:.1},\"snr\":{:.1},\"dt\":{:.2},\"msg\":\"{}\"}}",
                decoded.freq_hz, decoded.snr_db, decoded.dt_sec, escaped);
            let mut out = stdout.lock(); writeln!(out, "{}", line).ok(); let _ = out.flush();
        }
    }
}
fn load_wav_12khz(path: &str) -> Result<Vec<i16>, Box<dyn std::error::Error>> {
    let mut reader = hound::WavReader::open(path)?;
    let spec = reader.spec();
    if spec.channels != 1 { return Err(format!("mono expected, got {} ch", spec.channels).into()); }
    if spec.sample_rate != 12000 { eprintln!("[mfsk-decode] warning: {} Hz", spec.sample_rate); }
    let samples: Vec<i16> = match (spec.sample_format, spec.bits_per_sample) {
        (hound::SampleFormat::Int, 16) => reader.samples::<i16>().map(|s| s.unwrap_or(0)).collect(),
        (hound::SampleFormat::Float, _) => reader.samples::<f32>().map(|s| (s.unwrap_or(0.0).clamp(-1.0, 1.0) * 32767.0) as i16).collect(),
        (hound::SampleFormat::Int, b) => { let scale = (1i64 << (b-1)) as f64; reader.samples::<i32>().map(|s| ((s.unwrap_or(0) as f64 / scale) * 32767.0) as i16).collect() }
    };
    Ok(samples)
}
