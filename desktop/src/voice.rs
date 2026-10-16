//! Microphone recording to WAV for voice attachments.

#[cfg(feature = "voice")]
mod imp {
    use std::path::Path;
    use std::sync::{Arc, Mutex};
    use std::time::Instant;

    use anyhow::{anyhow, Result};
    use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};

    pub struct Recorder {
        stream: cpal::Stream,
        samples: Arc<Mutex<Vec<f32>>>,
        rate: u32,
        channels: u16,
        pub started: Instant,
    }

    impl Recorder {
        pub fn start() -> Result<Self> {
            let device = cpal::default_host().default_input_device().ok_or_else(|| anyhow!("no microphone found"))?;
            let config = device.default_input_config()?;
            let samples = Arc::new(Mutex::new(Vec::<f32>::new()));
            let (rate, channels) = (config.sample_rate().0, config.channels());
            let err = |e| eprintln!("recording error: {e}");
            let s = samples.clone();
            let stream = match config.sample_format() {
                cpal::SampleFormat::F32 => device.build_input_stream(&config.into(), move |d: &[f32], _: &_| s.lock().unwrap().extend_from_slice(d), err, None)?,
                cpal::SampleFormat::I16 => device.build_input_stream(&config.into(), move |d: &[i16], _: &_| s.lock().unwrap().extend(d.iter().map(|x| *x as f32 / i16::MAX as f32)), err, None)?,
                cpal::SampleFormat::U16 => device.build_input_stream(&config.into(), move |d: &[u16], _: &_| s.lock().unwrap().extend(d.iter().map(|x| (*x as f32 - 32768.0) / 32768.0)), err, None)?,
                f => return Err(anyhow!("unsupported sample format {f:?}")),
            };
            stream.play()?;
            Ok(Self { stream, samples, rate, channels, started: Instant::now() })
        }

        pub fn finish(self, path: &Path) -> Result<()> {
            drop(self.stream);
            let spec = hound::WavSpec { channels: self.channels, sample_rate: self.rate, bits_per_sample: 16, sample_format: hound::SampleFormat::Int };
            let mut w = hound::WavWriter::create(path, spec)?;
            for s in self.samples.lock().unwrap().iter() {
                w.write_sample((s.clamp(-1.0, 1.0) * i16::MAX as f32) as i16)?;
            }
            w.finalize()?;
            Ok(())
        }
    }
}

#[cfg(not(feature = "voice"))]
mod imp {
    use std::path::Path;
    use std::time::Instant;

    pub struct Recorder {
        pub started: Instant,
    }

    impl Recorder {
        pub fn start() -> anyhow::Result<Self> {
            anyhow::bail!("this build has no voice recording (enable the `voice` feature)")
        }

        pub fn finish(self, _path: &Path) -> anyhow::Result<()> {
            Ok(())
        }
    }
}

pub use imp::Recorder;
