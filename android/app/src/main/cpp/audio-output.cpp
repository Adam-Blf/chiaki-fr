// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include "audio-output.h"

#include "circular-buf.hpp"

#include <chiaki/log.h>
#include <chiaki/thread.h>

#include <oboe/Oboe.h>

#include <atomic>
#include <algorithm>
#include <string.h>

#define BUFFER_CHUNK_SIZE 1024
#define BUFFER_CHUNKS_COUNT 32
#define LOW_LATENCY_MAX_BUFFERED_MS 40 // audio queued beyond this is discarded in low latency mode

using AudioBuffer = CircularBuffer<BUFFER_CHUNKS_COUNT, BUFFER_CHUNK_SIZE>;

class AudioOutput;

class AudioOutputCallback: public oboe::AudioStreamCallback
{
private:
	AudioOutput *audio_output;

public:
	AudioOutputCallback(AudioOutput *audio_output) : audio_output(audio_output) {}
	oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) override;
	void onErrorBeforeClose(oboe::AudioStream *stream, oboe::Result error) override;
	void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;
};

struct AudioOutput
{
	ChiakiLog *log;
	oboe::ManagedStream stream;
	AudioOutputCallback stream_callback;
	AudioBuffer buf;
	bool low_latency = false;
	std::atomic<int64_t> fill_bytes { 0 }; // bytes pushed minus bytes popped
	int64_t max_fill_bytes = 0; // only used when low_latency, set once the stream is open

	AudioOutput() : stream_callback(this) {}
};

extern "C" void *android_chiaki_audio_output_new(ChiakiLog *log, bool low_latency)
{
	auto r = new AudioOutput();
	r->log = log;
	r->low_latency = low_latency;
	return r;
}

extern "C" void android_chiaki_audio_output_free(void *audio_output)
{
	if(!audio_output)
		return;
	auto ao = reinterpret_cast<AudioOutput *>(audio_output);
	ao->stream = nullptr;
	delete ao;
}

extern "C" void android_chiaki_audio_output_settings(uint32_t channels, uint32_t rate, void *audio_output)
{
	auto ao = reinterpret_cast<AudioOutput *>(audio_output);

	oboe::AudioStreamBuilder builder;
	builder.setPerformanceMode(oboe::PerformanceMode::LowLatency)
		->setSharingMode(oboe::SharingMode::Exclusive)
		->setUsage(oboe::Usage::Game)
		->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::None)
		->setFormat(oboe::AudioFormat::I16)
		->setChannelCount(channels)
		->setSampleRate(rate)
		->setCallback(&ao->stream_callback);

	auto result = builder.openManagedStream(ao->stream);
	if(result != oboe::Result::OK)
	{
		CHIAKI_LOGE(ao->log, "Audio Output failed to open Oboe stream: %s", oboe::convertToText(result));
		return;
	}
	CHIAKI_LOGI(ao->log, "Audio Output opened Oboe stream");

	if(ao->low_latency)
	{
		int32_t burst = ao->stream->getFramesPerBurst();
		auto size_result = ao->stream->setBufferSizeInFrames(burst * 2);
		if(size_result)
			CHIAKI_LOGI(ao->log, "Audio Output buffer size set to %d frames", (int)size_result.value());
		else
			CHIAKI_LOGW(ao->log, "Audio Output failed to set buffer size: %s", oboe::convertToText(size_result.error()));
		ao->max_fill_bytes = (int64_t)ao->stream->getSampleRate() * ao->stream->getBytesPerFrame() * LOW_LATENCY_MAX_BUFFERED_MS / 1000;
	}

	result = ao->stream->start();
	if(result == oboe::Result::OK)
		CHIAKI_LOGI(ao->log, "Audio Output started Oboe stream");
	else
		CHIAKI_LOGE(ao->log, "Audio Output failed to start Oboe stream: %s", oboe::convertToText(result));
}

extern "C" void android_chiaki_audio_output_frame(int16_t *buf, size_t samples_count, void *audio_output)
{
	auto ao = reinterpret_cast<AudioOutput *>(audio_output);

	size_t buf_size = samples_count * sizeof(int16_t);
	size_t pushed = ao->buf.Push(reinterpret_cast<uint8_t *>(buf), buf_size);
	ao->fill_bytes.fetch_add((int64_t)pushed, std::memory_order_relaxed);
	if(pushed < buf_size)
		CHIAKI_LOGW(ao->log, "Audio Output Buffer Overflow!");
}

oboe::DataCallbackResult AudioOutputCallback::onAudioReady(oboe::AudioStream *stream, void *audio_data, int32_t num_frames)
{
	if(stream->getFormat() != oboe::AudioFormat::I16)
	{
		CHIAKI_LOGE(audio_output->log, "Oboe stream has invalid format in callback");
		return oboe::DataCallbackResult::Stop;
	}

	int32_t bytes_per_frame = stream->getBytesPerFrame();
	size_t buf_size_requested = static_cast<size_t>(bytes_per_frame * num_frames);
	auto buf = reinterpret_cast<uint8_t *>(audio_data);

	if(audio_output->low_latency)
	{
		// Drop the oldest audio beyond the target so the delay can't build up over time
		int64_t excess = audio_output->fill_bytes.load(std::memory_order_relaxed) - static_cast<int64_t>(buf_size_requested) - audio_output->max_fill_bytes;
		uint8_t scratch[BUFFER_CHUNK_SIZE];
		while(excess > 0)
		{
			size_t want = static_cast<size_t>(std::min<int64_t>(excess, sizeof(scratch)));
			size_t discarded = audio_output->buf.Pop(scratch, want);
			if(discarded == 0)
				break;
			audio_output->fill_bytes.fetch_sub(static_cast<int64_t>(discarded), std::memory_order_relaxed);
			excess -= static_cast<int64_t>(discarded);
		}
	}

	size_t buf_size_delivered = audio_output->buf.Pop(buf, buf_size_requested);
	audio_output->fill_bytes.fetch_sub(static_cast<int64_t>(buf_size_delivered), std::memory_order_relaxed);
	//CHIAKI_LOGW(audio_output->log, "Delivered %llu", (unsigned long long)buf_size_delivered);

	if(buf_size_delivered < buf_size_requested)
	{
		CHIAKI_LOGV(audio_output->log, "Audio Output Buffer Underflow!");
		memset(buf + buf_size_delivered, 0, buf_size_requested - buf_size_delivered);
	}

	return oboe::DataCallbackResult::Continue;
}

void AudioOutputCallback::onErrorBeforeClose(oboe::AudioStream *stream, oboe::Result error)
{
	CHIAKI_LOGE(audio_output->log, "Oboe reported error before close: %s", oboe::convertToText(error));
}

void AudioOutputCallback::onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error)
{
	CHIAKI_LOGE(audio_output->log, "Oboe reported error after close: %s", oboe::convertToText(error));
}
