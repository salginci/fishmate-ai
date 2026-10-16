/* Hand-written in place of autoconf's output: the only build that matters
   here is the NDK one, and it has a fixed answer to every question configure
   would ask. */
#ifndef SPEEXDSP_LOCAL_CONFIG_H
#define SPEEXDSP_LOCAL_CONFIG_H

/* Floating point: the phones this ships to all have an FPU, and the fixed
   point path trades accuracy for speed we do not need. */
#define FLOATING_POINT

/* The bundled FFT, so there is no external dependency to resolve. */
#define USE_KISS_FFT

#define HAVE_STDINT_H 1
#define EXPORT __attribute__((visibility("default")))

#endif
