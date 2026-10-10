import { useEffect, useMemo, useRef, useState } from 'react'
import { FishingModeController, type FishingModeState } from '../live/FishingModeController'
import { Avatar } from './Avatar'

const STATUS: Record<FishingModeState['phase'], string> = {
  idle: 'Idle',
  connecting: 'Connecting…',
  listening: 'Listening',
  speaking: 'Speaking',
  toolCall: 'Checking…',
  reconnecting: 'Reconnecting…',
  error: 'Something went wrong',
}

export function FishingModeScreen({
  language,
  voice,
  onExit,
}: {
  language: string
  voice: string
  onExit: () => void
}) {
  const controller = useMemo(() => new FishingModeController(), [])
  const [state, setState] = useState<FishingModeState>(controller.getState())
  const transcriptRef = useRef<HTMLDivElement>(null)

  useEffect(() => controller.subscribe(setState), [controller])

  useEffect(() => {
    void controller.start(language, false, voice) // voice first; the angler opts into video
    return () => {
      void controller.stop()
    }
  }, [controller, language, voice])

  // Follow the conversation; the angler is not scrolling.
  useEffect(() => {
    const node = transcriptRef.current
    if (node) node.scrollTop = node.scrollHeight
  }, [state.transcript, state.interimTranscript])

  return (
    <div className="fishing">
      <div className="topbar">
        <h2 style={{ fontSize: 17 }}>Fishing Mode</h2>
        <button
          className="end-button"
          onClick={() => {
            void controller.stop().then(onExit)
          }}
        >
          End
        </button>
      </div>

      <div className="orb-wrap">
        <Avatar phase={state.phase} isMuted={state.isMuted} getLevel={controller.getOutputLevel} />
        <div className="status">{STATUS[state.phase]}</div>
        <div className={`status-sub${state.errorMessage ? ' is-error' : ''}`}>
          {state.errorMessage ?? (state.isWatching ? 'I can see your camera.' : 'Ask me anything.')}
        </div>
        {state.phase === 'toolCall' && state.toolActivity.length > 0 && (
          <div className="chip">{state.toolActivity[state.toolActivity.length - 1]}</div>
        )}
      </div>

      {/*
        "Is the camera actually on?" is otherwise unanswerable: frames stream
        silently and the only feedback would be a counter. This is the exact
        JPEG that went to the model.
      */}
      {state.cameraEnabled && state.previewFrame && (
        <div className="vision">
          <img
            src={state.previewFrame.url}
            alt="The latest camera frame sent to the assistant"
            className={state.previewFrame.sent ? '' : 'paused'}
          />
          <div className="badge">
            {state.previewFrame.sent ? `Seeing this · ${state.framesSent} frames` : 'Paused while speaking'}
          </div>
        </div>
      )}

      <div className="transcript" ref={transcriptRef}>
        {state.transcript.length === 0 && !state.interimTranscript && (
          <div className="line">
            <div className="text interim">The conversation will appear here.</div>
          </div>
        )}
        {state.transcript.map((line) => (
          <div className={`line ${line.speaker}`} key={line.id}>
            <div className="who">{line.speaker === 'user' ? 'You' : 'FishMate'}</div>
            <div className="text">{line.text}</div>
            {/* Absent until the translation arrives: a spinner per line would
                flicker through the whole conversation. */}
            {state.showTranslation && line.translation && (
              <div className="translation">{line.translation}</div>
            )}
          </div>
        ))}
        {state.interimTranscript && (
          <div className="line user">
            <div className="who">You</div>
            <div className="text interim">{state.interimTranscript}</div>
          </div>
        )}
      </div>

      <div className="controls">
        <button
          className={`control${state.showTranslation ? ' active' : ''}`}
          onClick={() => controller.toggleTranslation()}
        >
          <span className="icon">🌐</span>
          {state.subtitleLanguage.toUpperCase()}
        </button>

        <button
          className={`control${state.isMuted ? '' : ' active'}`}
          onClick={() => controller.setMuted(!state.isMuted)}
        >
          <span className="icon">{state.isMuted ? '🔇' : '🎙️'}</span>
          {state.isMuted ? 'Unmute' : 'Mute'}
        </button>

        <button
          className={`control${state.cameraEnabled ? ' active' : ''}`}
          onClick={() => void controller.toggleCamera()}
        >
          <span className="icon">{state.cameraEnabled ? '👁️' : '🚫'}</span>
          {state.cameraEnabled ? 'Camera on' : 'Camera off'}
        </button>

        {state.cameraEnabled ? (
          <button className="control" onClick={() => void controller.switchCamera()}>
            <span className="icon">🔄</span>
            Switch
          </button>
        ) : (
          <button className="control" onClick={() => void controller.captureAndSend()}>
            <span className="icon">📷</span>
            Look at this
          </button>
        )}
      </div>

      {/* Visible health, because this is a showcase of the Live API and the
          numbers that tell you whether it is working should not be hidden in a
          console. `repeats` is the one to watch: it is the symptom of the model
          hearing itself and restarting the same sentence. */}
      <div className="diagnostics">
        <span>{state.connection.state}</span>
        <span>
          frames {state.framesSent}/{state.framesSent + state.framesSkipped}
        </span>
        <span>interruptions {state.interruptions}</span>
        <span className={state.repeatedSentences > 0 ? 'bad' : 'good'}>
          repeats {state.repeatedSentences}
        </span>
        <span>reply {state.replyLatencyMs !== null ? `${(state.replyLatencyMs / 1000).toFixed(1)}s` : '—'}</span>
        <span className={state.underruns > 0 ? 'bad' : 'good'}>breaks {state.underruns}</span>
        <span>tokens {state.totalTokens}</span>
      </div>
    </div>
  )
}
