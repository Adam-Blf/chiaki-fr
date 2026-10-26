// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_JNI_VIDEO_DECODER_H
#define CHIAKI_JNI_VIDEO_DECODER_H

#include <jni.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>

#include <chiaki/thread.h>
#include <chiaki/log.h>

typedef struct AMediaCodec AMediaCodec;
typedef struct ANativeWindow ANativeWindow;

#define VIDEO_DECODER_ENQUEUE_RING_SIZE 256 // must be a power of two, indexed by pts & (size - 1)

typedef struct android_chiaki_video_stats_t
{
	float fps; // frames rendered during the last full second
	float decode_ms; // average enqueue to output time during the last full second
	uint64_t frames_dropped; // decoded frames discarded as stale instead of rendered
	uint64_t frames_lost; // sum of the frames_lost values reported by the video receiver
} AndroidChiakiVideoStats;

typedef struct android_chiaki_video_decoder_t
{
	ChiakiLog *log;
	ChiakiMutex codec_mutex;
	AMediaCodec *codec;
	ANativeWindow *window;
	uint64_t timestamp_cur;
	ChiakiThread output_thread;
	bool shutdown_output;
	int32_t target_width;
	int32_t target_height;
	ChiakiCodec target_codec;
	bool low_latency;

	// Written by the input thread, read by the output thread
	_Atomic uint64_t enqueue_time_us[VIDEO_DECODER_ENQUEUE_RING_SIZE];
	atomic_bool drop_pending;

	// Published by the output thread once per second, read by any thread
	atomic_uint_fast32_t fps_x100;
	atomic_uint_fast32_t decode_us_avg;
	atomic_uint_fast64_t frames_dropped;
	atomic_uint_fast64_t frames_lost;
} AndroidChiakiVideoDecoder;

ChiakiErrorCode android_chiaki_video_decoder_init(AndroidChiakiVideoDecoder *decoder, ChiakiLog *log, int32_t target_width, int32_t target_height, ChiakiCodec codec, bool low_latency);
void android_chiaki_video_decoder_fini(AndroidChiakiVideoDecoder *decoder);
void android_chiaki_video_decoder_set_surface(AndroidChiakiVideoDecoder *decoder, JNIEnv *env, jobject surface);
void android_chiaki_video_decoder_get_stats(AndroidChiakiVideoDecoder *decoder, AndroidChiakiVideoStats *stats);
bool android_chiaki_video_decoder_video_sample(uint8_t *buf, size_t buf_size, int32_t frames_lost, bool frame_recovered, void *user);

#endif