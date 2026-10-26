// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include "video-decoder.h"

#include <jni.h>

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <android/native_window_jni.h>

#include <string.h>
#include <time.h>
#include <unistd.h>

#define INPUT_BUFFER_TIMEOUT_MS 3
#define OUTPUT_BUFFER_TIMEOUT_US 100000 // also bounds how stale the published stats can get
#define MAX_INPUT_SIZE (2 * 1024 * 1024) // large enough for any frame, avoids splitting across input buffers
#define STATS_WINDOW_NS 1000000000ULL

static void *android_chiaki_video_decoder_output_thread_func(void *user);

static uint64_t now_monotonic_ns(void)
{
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return (uint64_t)ts.tv_sec * 1000000000ULL + (uint64_t)ts.tv_nsec;
}

ChiakiErrorCode android_chiaki_video_decoder_init(AndroidChiakiVideoDecoder *decoder, ChiakiLog *log, int32_t target_width, int32_t target_height, ChiakiCodec codec, bool low_latency)
{
	decoder->log = log;
	decoder->codec = NULL;
	decoder->window = NULL;
	decoder->timestamp_cur = 0;
	decoder->target_width = target_width;
	decoder->target_height = target_height;
	decoder->target_codec = codec;
	decoder->shutdown_output = false;
	decoder->low_latency = low_latency;
	for(size_t i=0; i<VIDEO_DECODER_ENQUEUE_RING_SIZE; i++)
		atomic_init(&decoder->enqueue_time_us[i], 0);
	atomic_init(&decoder->drop_pending, false);
	atomic_init(&decoder->fps_x100, 0);
	atomic_init(&decoder->decode_us_avg, 0);
	atomic_init(&decoder->frames_dropped, 0);
	atomic_init(&decoder->frames_lost, 0);
	return chiaki_mutex_init(&decoder->codec_mutex, false);
}

static void kill_decoder(AndroidChiakiVideoDecoder *decoder)
{
	chiaki_mutex_lock(&decoder->codec_mutex);
	decoder->shutdown_output = true;
	ssize_t codec_buf_index = AMediaCodec_dequeueInputBuffer(decoder->codec, 1000);
	if(codec_buf_index >= 0)
	{
		CHIAKI_LOGI(decoder->log, "Video Decoder sending EOS buffer");
		AMediaCodec_queueInputBuffer(decoder->codec, (size_t)codec_buf_index, 0, 0, decoder->timestamp_cur++, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
		AMediaCodec_stop(decoder->codec);
		chiaki_mutex_unlock(&decoder->codec_mutex);
		chiaki_thread_join(&decoder->output_thread, NULL);
	}
	else
	{
		CHIAKI_LOGE(decoder->log, "Failed to get input buffer for shutting down Video Decoder!");
		AMediaCodec_stop(decoder->codec);
		chiaki_mutex_unlock(&decoder->codec_mutex);
		// the output thread sees the stop as an error and exits on shutdown_output
		chiaki_thread_join(&decoder->output_thread, NULL);
	}
	AMediaCodec_delete(decoder->codec);
	decoder->codec = NULL;
	if(decoder->window)
	{
		ANativeWindow_release(decoder->window);
		decoder->window = NULL;
	}
	decoder->shutdown_output = false;
}

void android_chiaki_video_decoder_fini(AndroidChiakiVideoDecoder *decoder)
{
	if(decoder->codec)
		kill_decoder(decoder);
	chiaki_mutex_fini(&decoder->codec_mutex);
}

void android_chiaki_video_decoder_set_surface(AndroidChiakiVideoDecoder *decoder, JNIEnv *env, jobject surface)
{
	chiaki_mutex_lock(&decoder->codec_mutex);

	if(!surface)
	{
		bool has_codec = decoder->codec != NULL;
		chiaki_mutex_unlock(&decoder->codec_mutex); // kill_decoder() locks on its own
		if(has_codec)
		{
			kill_decoder(decoder);
			CHIAKI_LOGI(decoder->log, "Decoder shut down after surface was removed");
		}
		return;
	}

	if(decoder->codec)
	{
#if __ANDROID_API__ >= 23
		CHIAKI_LOGI(decoder->log, "Video decoder already initialized, swapping surface");
		ANativeWindow *new_window = surface ? ANativeWindow_fromSurface(env, surface) : NULL;
		AMediaCodec_setOutputSurface(decoder->codec, new_window);
		ANativeWindow_release(decoder->window);
		decoder->window = new_window;
#else
		CHIAKI_LOGE(decoder->log, "Video Decoder already initialized");
#endif
		goto beach;
	}

	decoder->window = ANativeWindow_fromSurface(env, surface);

	const char *mime = chiaki_codec_is_h265(decoder->target_codec) ? "video/hevc" : "video/avc";
	CHIAKI_LOGI(decoder->log, "Initializing decoder with mime %s (low latency: %s)", mime, decoder->low_latency ? "on" : "off");

	decoder->codec = AMediaCodec_createDecoderByType(mime);
	if(!decoder->codec)
	{
		CHIAKI_LOGE(decoder->log, "Failed to create AMediaCodec for mime type %s", mime);
		goto error_surface;
	}

	AMediaFormat *format = AMediaFormat_new();
	AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, mime);
	AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, decoder->target_width);
	AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, decoder->target_height);
	AMediaFormat_setInt32(format, "max-input-size", MAX_INPUT_SIZE);
	if(decoder->low_latency)
	{
		AMediaFormat_setInt32(format, "low-latency", 1);
		AMediaFormat_setInt32(format, "priority", 0); // realtime
		AMediaFormat_setInt32(format, "operating-rate", 32767);
		AMediaFormat_setInt32(format, "allow-frame-drop", 1);
		AMediaFormat_setInt32(format, "vendor.nvidia.disable-output-reorder", 1);
		AMediaFormat_setInt32(format, "vendor.low-latency.enable", 1);
		AMediaFormat_setInt32(format, "vendor.rtc-ext-dec-low-latency.enable", 1);
		AMediaFormat_setInt32(format, "vendor.qti-ext-dec-low-latency.enable", 1);
	}

	media_status_t r = AMediaCodec_configure(decoder->codec, format, decoder->window, NULL, 0);
	if(r != AMEDIA_OK)
	{
		CHIAKI_LOGE(decoder->log, "AMediaCodec_configure() failed: %d", (int)r);
		AMediaFormat_delete(format);
		goto error_codec;
	}

	r = AMediaCodec_start(decoder->codec);
	AMediaFormat_delete(format);
	if(r != AMEDIA_OK)
	{
		CHIAKI_LOGE(decoder->log, "AMediaCodec_start() failed: %d", (int)r);
		goto error_codec;
	}

	ChiakiErrorCode err = chiaki_thread_create(&decoder->output_thread, android_chiaki_video_decoder_output_thread_func, decoder);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		CHIAKI_LOGE(decoder->log, "Failed to create output thread for AMediaCodec");
		goto error_codec;
	}

	goto beach;

error_codec:
	AMediaCodec_delete(decoder->codec);
	decoder->codec = NULL;

error_surface:
	ANativeWindow_release(decoder->window);
	decoder->window = NULL;

beach:
	chiaki_mutex_unlock(&decoder->codec_mutex);
}

void android_chiaki_video_decoder_get_stats(AndroidChiakiVideoDecoder *decoder, AndroidChiakiVideoStats *stats)
{
	stats->fps = (float)atomic_load(&decoder->fps_x100) / 100.0f;
	stats->decode_ms = (float)atomic_load(&decoder->decode_us_avg) / 1000.0f;
	stats->frames_dropped = (uint64_t)atomic_load(&decoder->frames_dropped);
	stats->frames_lost = (uint64_t)atomic_load(&decoder->frames_lost);
}

bool android_chiaki_video_decoder_video_sample(uint8_t *buf, size_t buf_size, int32_t frames_lost, bool frame_recovered, void *user)
{
	bool r = true;
	AndroidChiakiVideoDecoder *decoder = user;

	if(frames_lost > 0)
	{
		atomic_fetch_add(&decoder->frames_lost, (uint_fast64_t)frames_lost);
		// whatever is still waiting in the output is older than the gap, don't show it
		if(!frame_recovered)
			atomic_store(&decoder->drop_pending, true);
	}

	chiaki_mutex_lock(&decoder->codec_mutex);

	if(!decoder->codec)
	{
		CHIAKI_LOGE(decoder->log, "Received video data, but decoder is not initialized!");
		goto beach;
	}

	// All parts of one frame share a pts so the output side can match it to the enqueue time
	uint64_t pts = decoder->timestamp_cur++;
	atomic_store(&decoder->enqueue_time_us[pts & (VIDEO_DECODER_ENQUEUE_RING_SIZE - 1)], now_monotonic_ns() / 1000);

	while(buf_size > 0)
	{
		ssize_t codec_buf_index = AMediaCodec_dequeueInputBuffer(decoder->codec, INPUT_BUFFER_TIMEOUT_MS * 1000);
		if(codec_buf_index < 0)
		{
			CHIAKI_LOGE(decoder->log, "Failed to get input buffer");
			r = false;
			goto beach;
		}

		size_t codec_buf_size;
		uint8_t *codec_buf = AMediaCodec_getInputBuffer(decoder->codec, (size_t)codec_buf_index, &codec_buf_size);
		if(!codec_buf)
		{
			CHIAKI_LOGE(decoder->log, "Failed to map input buffer");
			AMediaCodec_queueInputBuffer(decoder->codec, (size_t)codec_buf_index, 0, 0, pts, 0);
			r = false;
			goto beach;
		}
		size_t codec_sample_size = buf_size;
		if(codec_sample_size > codec_buf_size)
		{
			CHIAKI_LOGW(decoder->log, "Video sample of %llu bytes is bigger than the input buffer of %llu bytes, splitting",
					(unsigned long long)buf_size, (unsigned long long)codec_buf_size);
			codec_sample_size = codec_buf_size;
		}
		memcpy(codec_buf, buf, codec_sample_size);
		media_status_t qr = AMediaCodec_queueInputBuffer(decoder->codec, (size_t)codec_buf_index, 0, codec_sample_size, pts, 0);
		if(qr != AMEDIA_OK)
		{
			CHIAKI_LOGE(decoder->log, "AMediaCodec_queueInputBuffer() failed: %d", (int)qr);
			r = false;
			goto beach;
		}
		buf += codec_sample_size;
		buf_size -= codec_sample_size;
	}

beach:
	chiaki_mutex_unlock(&decoder->codec_mutex);
	return r;
}

typedef struct video_output_stats_t
{
	uint64_t window_start_ns;
	uint64_t frames;
	uint64_t decode_us_sum;
} VideoOutputStats;

static void output_stats_rendered(AndroidChiakiVideoDecoder *decoder, VideoOutputStats *st, int64_t pts)
{
	uint64_t enqueued_us = atomic_load(&decoder->enqueue_time_us[(uint64_t)pts & (VIDEO_DECODER_ENQUEUE_RING_SIZE - 1)]);
	uint64_t now_us = now_monotonic_ns() / 1000;
	if(enqueued_us && now_us >= enqueued_us)
		st->decode_us_sum += now_us - enqueued_us;
	st->frames++;
}

// Publish and reset the counters once per window, also reached when no frame arrived
static void output_stats_roll(AndroidChiakiVideoDecoder *decoder, VideoOutputStats *st)
{
	uint64_t now_ns = now_monotonic_ns();
	uint64_t elapsed_ns = now_ns - st->window_start_ns;
	if(elapsed_ns < STATS_WINDOW_NS)
		return;
	atomic_store(&decoder->fps_x100, (uint_fast32_t)(st->frames * 100ULL * 1000000000ULL / elapsed_ns));
	atomic_store(&decoder->decode_us_avg, (uint_fast32_t)(st->frames ? st->decode_us_sum / st->frames : 0));
	st->window_start_ns = now_ns;
	st->frames = 0;
	st->decode_us_sum = 0;
}

static void output_render_now(AndroidChiakiVideoDecoder *decoder, size_t index)
{
	if(AMediaCodec_releaseOutputBufferAtTime(decoder->codec, index, (int64_t)now_monotonic_ns()) != AMEDIA_OK)
		AMediaCodec_releaseOutputBuffer(decoder->codec, index, true);
}

static void *android_chiaki_video_decoder_output_thread_func(void *user)
{
	AndroidChiakiVideoDecoder *decoder = user;
	VideoOutputStats stats = { now_monotonic_ns(), 0, 0 };

	while(1)
	{
		AMediaCodecBufferInfo info;
		ssize_t status = AMediaCodec_dequeueOutputBuffer(decoder->codec, &info, OUTPUT_BUFFER_TIMEOUT_US);
		output_stats_roll(decoder, &stats);
		if(status >= 0)
		{
			bool eos = (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0;
			if(!decoder->low_latency)
			{
				AMediaCodec_releaseOutputBuffer(decoder->codec, (size_t)status, info.size != 0);
				if(info.size != 0)
					output_stats_rendered(decoder, &stats, info.presentationTimeUs);
			}
			else
			{
				// Keep only the newest decoded frame, older ones are stale by definition
				size_t newest = (size_t)status;
				AMediaCodecBufferInfo newest_info = info;
				while(!eos)
				{
					AMediaCodecBufferInfo next_info;
					ssize_t next = AMediaCodec_dequeueOutputBuffer(decoder->codec, &next_info, 0);
					if(next == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED || next == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED)
						continue;
					if(next < 0)
						break;
					AMediaCodec_releaseOutputBuffer(decoder->codec, newest, false);
					atomic_fetch_add(&decoder->frames_dropped, 1);
					newest = (size_t)next;
					newest_info = next_info;
					eos = (next_info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0;
				}
				if(newest_info.size == 0)
				{
					AMediaCodec_releaseOutputBuffer(decoder->codec, newest, false);
				}
				else if(atomic_exchange(&decoder->drop_pending, false))
				{
					// frames were lost and not recovered, what is decoded now is corrupt
					AMediaCodec_releaseOutputBuffer(decoder->codec, newest, false);
					atomic_fetch_add(&decoder->frames_dropped, 1);
				}
				else
				{
					output_render_now(decoder, newest);
					output_stats_rendered(decoder, &stats, newest_info.presentationTimeUs);
				}
			}
			if(eos)
			{
				CHIAKI_LOGI(decoder->log, "AMediaCodec reported EOS");
				break;
			}
		}
		else
		{
			if(status == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED)
				CHIAKI_LOGI(decoder->log, "AMediaCodec output format changed");
			if(status == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED || status == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED)
				continue;
			// timeout or error: the codec may have been stopped under us
			chiaki_mutex_lock(&decoder->codec_mutex);
			bool shutdown = decoder->shutdown_output;
			chiaki_mutex_unlock(&decoder->codec_mutex);
			if(shutdown)
			{
				CHIAKI_LOGI(decoder->log, "Video Decoder Output Thread detected shutdown after reported error");
				break;
			}
			if(status != AMEDIACODEC_INFO_TRY_AGAIN_LATER)
				usleep(1000); // don't spin while the codec reports an error
		}
	}

	CHIAKI_LOGI(decoder->log, "Video Decoder Output Thread exiting");

	return NULL;
}
