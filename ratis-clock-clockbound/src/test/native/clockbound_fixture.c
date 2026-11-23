/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#ifdef RATIS_HAVE_CLOCKBOUND_HEADER
#undef errno
#include <clockbound.h>
#else
typedef struct clockbound_ctx clockbound_ctx;
typedef struct {
    int kind;
    int errno;
    char detail[128];
} clockbound_err;
typedef struct {
    struct timespec earliest;
    struct timespec latest;
    int clock_status;
} clockbound_now_result;
#endif

struct clockbound_ctx {
    pthread_t owner;
};

static atomic_int mode;
static atomic_int opens;
static atomic_int closes;
static atomic_int active;
static pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t condition = PTHREAD_COND_INITIALIZER;
static int released;

static clockbound_err *fail(clockbound_err *err, const char *message) {
    memset(err, 0, sizeof(*err));
    err->kind = 1;
    err->errno = 5;
    /* The upstream C header marks the caller-owned output buffer const; Rust writes it directly. */
    const size_t length = strlen(message) < sizeof(err->detail) - 1 ? strlen(message) : sizeof(err->detail) - 1;
    memcpy((void *) err->detail, message, length);
    return err;
}

void fixtureMode(int value) {
    pthread_mutex_lock(&mutex);
    released = 0;
    atomic_store(&mode, value);
    pthread_mutex_unlock(&mutex);
}

int fixtureOpenCount(void) { return atomic_load(&opens); }
int fixtureCloseCount(void) { return atomic_load(&closes); }
int fixtureActiveCount(void) { return atomic_load(&active); }
int fixtureResultSize(void) { return sizeof(clockbound_now_result); }
int fixtureErrorSize(void) { return sizeof(clockbound_err); }

void fixtureRelease(void) {
    pthread_mutex_lock(&mutex);
    released = 1;
    pthread_cond_broadcast(&condition);
    pthread_mutex_unlock(&mutex);
}

uint64_t fixtureCounter(void) {
#if defined(__x86_64__)
    uint32_t low, high;
    __asm__ volatile("lfence\n\trdtsc\n\tlfence" : "=a"(low), "=d"(high) :: "memory");
    return ((uint64_t) high << 32) | low;
#elif defined(__aarch64__)
    uint64_t value;
    __asm__ volatile("isb\n\tmrs %0, cntvct_el0\n\tisb" : "=r"(value) :: "memory");
    return value;
#else
#error Unsupported native test architecture
#endif
}

#ifndef RATIS_LEGACY_API
clockbound_ctx *clockbound_open_with(const char *shm, const char *vmclock, clockbound_err *err) {
    (void) shm;
    (void) vmclock;
    if (atomic_load(&mode) == 7) {
        fail(err, "fixture open failure");
        return NULL;
    }
    if (atomic_load(&mode) == 8 && strcmp(shm, "/tmp/clock-\xc3\xa9") != 0) {
        fail(err, "path is not UTF-8");
        return NULL;
    }
    clockbound_ctx *ctx = malloc(sizeof(*ctx));
    if (ctx == NULL) {
        fail(err, "fixture allocation failure");
        return NULL;
    }
    ctx->owner = pthread_self();
    atomic_fetch_add(&opens, 1);
    return ctx;
}
#endif

clockbound_err *clockbound_now(clockbound_ctx *ctx, clockbound_now_result *res, clockbound_err *err) {
#ifdef RATIS_LEGACY_API
    abort();
#endif
    if (!pthread_equal(ctx->owner, pthread_self())) {
        return fail(err, "cross-thread context use");
    }
    const int value = atomic_load(&mode);
    if (value == 4) {
        return fail(err, "fixture failure");
    }
    if (value == 6) {
        pthread_mutex_lock(&mutex);
        atomic_fetch_add(&active, 1);
        while (!released) {
            pthread_cond_wait(&condition, &mutex);
        }
        atomic_fetch_sub(&active, 1);
        pthread_mutex_unlock(&mutex);
    }
    memset(res, 0, sizeof(*res));
    res->earliest.tv_sec = -1;
    res->earliest.tv_nsec = 999999999;
    res->latest.tv_sec = 0;
    res->latest.tv_nsec = value == 5 ? 1000000000 : 17;
    res->clock_status = value == 1 ? 0 : value == 2 ? 2 : value == 3 ? 3 : 1;
    return NULL;
}

clockbound_err *clockbound_close(clockbound_ctx *ctx, clockbound_err *err) {
    if (atomic_load(&active) != 0) {
        return fail(err, "closed during active read");
    }
    atomic_fetch_add(&closes, 1);
    free(ctx);
    if (atomic_load(&mode) == 9) {
        return fail(err, "fixture close failure");
    }
    return NULL;
}
