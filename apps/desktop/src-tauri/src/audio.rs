use std::fs::File;
use std::io::BufWriter;
use std::path::{Path, PathBuf};
use std::sync::mpsc;
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use std::time::Duration;

use anyhow::{anyhow, bail, Context, Result};
use chrono::{DateTime, Utc};
use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::SampleFormat;
use sha2::{Digest, Sha256};
use uuid::Uuid;

const TARGET_RATE: u32 = 16_000;
const MAX_RECORDING_SECONDS: usize = 20 * 60;

pub struct AudioManager {
    session: Mutex<Option<RecordingSession>>,
}

struct RecordingSession {
    stop: mpsc::Sender<()>,
    join: JoinHandle<Result<CapturedSamples, String>>,
    started_at: DateTime<Utc>,
}

struct CapturedSamples {
    samples: Vec<f32>,
    sample_rate: u32,
}

pub struct CapturedAudio {
    pub attachment_id: String,
    pub path: PathBuf,
    pub media_type: String,
    pub byte_size: i64,
    pub sha256: String,
    pub started_at: DateTime<Utc>,
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RecordingStatus {
    pub started_at: DateTime<Utc>,
}

impl AudioManager {
    pub fn new() -> Self {
        Self {
            session: Mutex::new(None),
        }
    }

    pub fn start(&self) -> Result<RecordingStatus> {
        let mut guard = self.session.lock().map_err(|_| anyhow!("录音状态不可用"))?;
        if guard.is_some() {
            bail!("已在录音");
        }
        let (ready_tx, ready_rx) = mpsc::channel::<Result<(u32, u16), String>>();
        let (stop_tx, stop_rx) = mpsc::channel::<()>();
        let join = std::thread::spawn(move || capture_thread(ready_tx, stop_rx));
        match ready_rx.recv_timeout(Duration::from_secs(8)) {
            Ok(Ok(_)) => {
                let started_at = Utc::now();
                *guard = Some(RecordingSession {
                    stop: stop_tx,
                    join,
                    started_at,
                });
                Ok(RecordingStatus { started_at })
            }
            Ok(Err(error)) => {
                let _ = join.join();
                Err(anyhow!(error))
            }
            Err(_) => {
                let _ = stop_tx.send(());
                let _ = join.join();
                Err(anyhow!(
                    "启动麦克风超时；请检查“系统设置 > 隐私与安全性 > 麦克风”"
                ))
            }
        }
    }

    pub fn stop(&self, root: &Path) -> Result<CapturedAudio> {
        let session = self
            .session
            .lock()
            .map_err(|_| anyhow!("录音状态不可用"))?
            .take()
            .ok_or_else(|| anyhow!("当前没有录音"))?;
        let _ = session.stop.send(());
        let captured = session
            .join
            .join()
            .map_err(|_| anyhow!("录音线程异常退出"))?
            .map_err(|error| anyhow!(error))?;
        if captured.samples.len() < (captured.sample_rate as usize / 10) {
            bail!("录音太短，未保存");
        }
        let resampled = resample(&captured.samples, captured.sample_rate, TARGET_RATE);
        if resampled.is_empty() {
            bail!("录音为空，未保存");
        }
        let attachment_id = Uuid::new_v4().to_string();
        let directory = root.join("attachments");
        std::fs::create_dir_all(&directory)?;
        let path = directory.join(format!("{attachment_id}.wav"));
        let temp = directory.join(format!("{attachment_id}.wav.tmp"));
        write_wav(&temp, &resampled)?;
        std::fs::rename(&temp, &path).context("publish WAV file")?;
        let bytes = std::fs::read(&path)?;
        let digest = Sha256::digest(&bytes);
        Ok(CapturedAudio {
            attachment_id,
            path,
            media_type: "audio/wav".to_string(),
            byte_size: bytes.len() as i64,
            sha256: digest.iter().map(|byte| format!("{byte:02x}")).collect(),
            started_at: session.started_at,
        })
    }
}

fn capture_thread(
    ready: mpsc::Sender<Result<(u32, u16), String>>,
    stop: mpsc::Receiver<()>,
) -> Result<CapturedSamples, String> {
    let host = cpal::default_host();
    let device = host
        .default_input_device()
        .ok_or_else(|| "找不到麦克风；请在系统设置中检查输入设备".to_string())?;
    let supported = device
        .default_input_config()
        .map_err(|error| format!("麦克风不可用：{error}；请检查麦克风权限"))?;
    let format = supported.sample_format();
    let config: cpal::StreamConfig = supported.into();
    let sample_rate = config.sample_rate.0;
    let channels = config.channels;
    let samples = Arc::new(Mutex::new(Vec::<f32>::new()));
    let error_slot = Arc::new(Mutex::new(None::<String>));
    let max_frames = sample_rate as usize * MAX_RECORDING_SECONDS;
    let callback_samples = samples.clone();
    let callback_error = error_slot.clone();
    let error_callback = move |error: cpal::StreamError| {
        if let Ok(mut slot) = callback_error.lock() {
            *slot = Some(error.to_string());
        }
    };
    let stream = match format {
        SampleFormat::F32 => device.build_input_stream(
            &config,
            move |data: &[f32], _| {
                push_samples(&callback_samples, data, channels, max_frames, |sample| {
                    sample
                })
            },
            error_callback,
            None,
        ),
        SampleFormat::I16 => device.build_input_stream(
            &config,
            move |data: &[i16], _| {
                push_samples(&callback_samples, data, channels, max_frames, |sample| {
                    sample as f32 / 32_768.0
                })
            },
            error_callback,
            None,
        ),
        SampleFormat::U16 => device.build_input_stream(
            &config,
            move |data: &[u16], _| {
                push_samples(&callback_samples, data, channels, max_frames, |sample| {
                    (sample as f32 - 32_768.0) / 32_768.0
                })
            },
            error_callback,
            None,
        ),
        other => {
            let _ = ready.send(Err(format!("不支持的麦克风采样格式：{other:?}")));
            return Err(format!("不支持的麦克风采样格式：{other:?}"));
        }
    }
    .map_err(|error| format!("创建麦克风输入流失败：{error}"))?;
    stream
        .play()
        .map_err(|error| format!("启动麦克风失败：{error}"))?;
    let _ = ready.send(Ok((sample_rate, channels)));
    let _ = stop.recv();
    drop(stream);
    if let Ok(slot) = error_slot.lock() {
        if let Some(error) = slot.as_ref() {
            return Err(format!("麦克风输入中断：{error}"));
        }
    }
    let samples = samples
        .lock()
        .map_err(|_| "录音缓冲区不可用".to_string())?
        .clone();
    Ok(CapturedSamples {
        samples,
        sample_rate,
    })
}

fn push_samples<T, F>(
    target: &Arc<Mutex<Vec<f32>>>,
    data: &[T],
    channels: u16,
    max_frames: usize,
    convert: F,
) where
    T: Copy,
    F: Fn(T) -> f32,
{
    let Ok(mut target) = target.lock() else {
        return;
    };
    let channels = channels.max(1) as usize;
    for frame in data.chunks(channels) {
        if target.len() >= max_frames {
            break;
        }
        let sample = frame.iter().map(|value| convert(*value)).sum::<f32>() / channels as f32;
        target.push(sample.clamp(-1.0, 1.0));
    }
}

fn resample(input: &[f32], source_rate: u32, target_rate: u32) -> Vec<f32> {
    if source_rate == target_rate {
        return input.to_vec();
    }
    if input.is_empty() || source_rate == 0 {
        return Vec::new();
    }
    let ratio = source_rate as f64 / target_rate as f64;
    let output_len = ((input.len() as f64) / ratio).floor() as usize;
    let mut output = Vec::with_capacity(output_len);
    for index in 0..output_len {
        let source_pos = index as f64 * ratio;
        let left = source_pos.floor() as usize;
        let right = (left + 1).min(input.len() - 1);
        let fraction = (source_pos - left as f64) as f32;
        output.push(input[left] * (1.0 - fraction) + input[right] * fraction);
    }
    output
}

fn write_wav(path: &Path, samples: &[f32]) -> Result<()> {
    let spec = hound::WavSpec {
        channels: 1,
        sample_rate: TARGET_RATE,
        bits_per_sample: 16,
        sample_format: hound::SampleFormat::Int,
    };
    let file = File::create(path)?;
    let mut writer = hound::WavWriter::new(BufWriter::new(file), spec)?;
    for sample in samples {
        writer.write_sample((sample.clamp(-1.0, 1.0) * i16::MAX as f32) as i16)?;
    }
    writer.finalize()?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn resample_halves_sample_count_for_double_rate() {
        let input = vec![0.0; 48_000];
        let output = resample(&input, 48_000, 24_000);
        assert_eq!(output.len(), 24_000);
    }

    #[test]
    fn resample_keeps_same_rate() {
        let input = vec![0.25, -0.25];
        assert_eq!(resample(&input, 16_000, 16_000), input);
    }
}
