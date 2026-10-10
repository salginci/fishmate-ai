/**
 * Errors never surface as exceptions in the UI.
 *
 * Mirrors `AppResult` on Android and `FishMateError` on the backend: every
 * failure becomes a presentable `{code, message}` rather than a stack trace.
 */
export type AppError = { code: string; message: string }

export type AppResult<T> = { ok: true; value: T } | { ok: false; error: AppError }

export const Ok = <T,>(value: T): AppResult<T> => ({ ok: true, value })

export const Err = <T,>(code: string, message: string): AppResult<T> => ({
  ok: false,
  error: { code, message },
})
