import { log } from '../core/log'

const TAG = 'CameraSource'

export type CameraFacing = 'environment' | 'user'

export type CameraFrame = {
  jpeg: Uint8Array
  width: number
  height: number
  capturedAtEpochMs: number
}

/**
 * Frame budget.
 *
 * Defaults chosen for cost, not fidelity: half a frame per second at 768 px is
 * enough for the model to identify a lure or a fish, and keeps a long session
 * affordable. The Live API takes realtime video as periodic stills, so this is
 * the intended shape rather than a compromise.
 */
export type FrameConfig = {
  framesPerSecond: number
  maxDimensionPx: number
  jpegQuality: number
}

export const DEFAULT_FRAME_CONFIG: FrameConfig = {
  framesPerSecond: 0.5,
  maxDimensionPx: 768,
  jpegQuality: 0.7,
}

/**
 * What the assistant can see.
 *
 * Frames are JPEG stills, not a video stream — the same abstraction the
 * Android app uses, so the Live layer never depends on the capture mechanism.
 */
export class CameraSource {
  private stream: MediaStream | null = null
  private video: HTMLVideoElement | null = null
  private canvas: HTMLCanvasElement | null = null
  private timer: number | null = null
  private facing: CameraFacing = 'environment'

  async start(facing: CameraFacing = 'environment'): Promise<void> {
    if (this.stream) return
    this.facing = facing
    this.stream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: facing, width: { ideal: 1280 }, height: { ideal: 720 } },
    })

    const video = document.createElement('video')
    video.srcObject = this.stream
    video.muted = true
    video.playsInline = true
    await video.play()
    this.video = video
    this.canvas = document.createElement('canvas')
    log.i(TAG, `camera started facing=${facing}`)
  }

  /** Throttled frames while running. Returns a stop function. */
  stream_(config: FrameConfig, onFrame: (frame: CameraFrame) => void): () => void {
    this.stopStreaming()
    const interval = config.framesPerSecond > 0 ? 1000 / config.framesPerSecond : 0
    if (interval <= 0) return () => {}

    this.timer = window.setInterval(() => {
      void this.captureStill(config).then((frame) => {
        if (frame) onFrame(frame)
      })
    }, interval)
    return () => this.stopStreaming()
  }

  /**
   * Grab a single frame. Used for "look at this", where a one-off capture is
   * far cheaper than turning on a stream.
   */
  async captureStill(config: FrameConfig = DEFAULT_FRAME_CONFIG): Promise<CameraFrame | null> {
    const video = this.video
    const canvas = this.canvas
    if (!video || !canvas || video.videoWidth === 0) return null

    const scale = Math.min(1, config.maxDimensionPx / Math.max(video.videoWidth, video.videoHeight))
    const width = Math.round(video.videoWidth * scale)
    const height = Math.round(video.videoHeight * scale)
    canvas.width = width
    canvas.height = height

    const ctx = canvas.getContext('2d')
    if (!ctx) return null
    ctx.drawImage(video, 0, 0, width, height)

    const blob = await new Promise<Blob | null>((resolve) =>
      canvas.toBlob(resolve, 'image/jpeg', config.jpegQuality),
    )
    if (!blob) return null

    return {
      jpeg: new Uint8Array(await blob.arrayBuffer()),
      width,
      height,
      capturedAtEpochMs: Date.now(),
    }
  }

  async switchCamera(): Promise<void> {
    const next: CameraFacing = this.facing === 'environment' ? 'user' : 'environment'
    this.release()
    await this.start(next)
  }

  private stopStreaming(): void {
    if (this.timer !== null) window.clearInterval(this.timer)
    this.timer = null
  }

  release(): void {
    this.stopStreaming()
    this.stream?.getTracks().forEach((track) => track.stop())
    this.video?.remove()
    this.stream = null
    this.video = null
    this.canvas = null
    log.i(TAG, 'camera released')
  }
}
