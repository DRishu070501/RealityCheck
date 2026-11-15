import type { FieldValues, Path, UseFormSetError } from 'react-hook-form'

import { ApiError } from '@/lib/api'
import type { ChangeType } from '@/lib/scenarioSchemas'

export const CHANGE_TYPE_LABELS: Record<ChangeType, string> = {
  RESOURCE_CAPACITY: 'Resource capacity',
  RESOURCE_UNAVAILABLE: 'Resource unavailable',
  RESOURCE_DOWNTIME: 'Resource downtime',
  DEMAND_VOLUME: 'Demand volume',
  PROCESSING_RATE: 'Processing rate',
  WORKING_SCHEDULE: 'Working schedule',
}

export const CHANGE_TYPE_HINTS: Record<ChangeType, string> = {
  RESOURCE_CAPACITY: 'Add or remove machines or people in a pool, or set its size.',
  RESOURCE_UNAVAILABLE: 'Take some units of a pool out for a period, for example absence.',
  RESOURCE_DOWNTIME: 'Take every unit of a pool out for a period, for example maintenance.',
  DEMAND_VOLUME: 'Scale the stones and carats of every batch not yet started.',
  PROCESSING_RATE: 'Make a stage faster or slower per processing slot.',
  WORKING_SCHEDULE: 'Add shifts to the factory calendar, or replace it.',
}

/** Wraps anything thrown by a request as an {@link ApiError}. */
export function asApiError(error: unknown, title: string): ApiError {
  return error instanceof ApiError ? error : new ApiError({ kind: 'network', title })
}

/**
 * Puts each server field error on the form field it names. `fieldMap` maps the server's field path
 * (for example `payload.value`, or a prefix such as `payload.shifts`) to a form field.
 *
 * @return the error, with the messages no field took, for a banner; null when every one was placed
 */
export function applyServerErrors<T extends FieldValues>(
  error: ApiError,
  setError: UseFormSetError<T>,
  fieldMap: Record<string, Path<T>>,
): { banner: ApiError | null; messages: string[] } {
  const unplaced: string[] = []
  for (const fieldError of error.fieldErrors) {
    const key = Object.keys(fieldMap)
      .sort((a, b) => b.length - a.length)
      .find((candidate) => fieldError.field === candidate || fieldError.field.startsWith(`${candidate}[`) || fieldError.field.startsWith(`${candidate}.`))
    const target = key === undefined ? undefined : fieldMap[key]
    if (target === undefined) {
      unplaced.push(`${fieldError.field}: ${fieldError.message}`)
    } else {
      setError(target, { type: 'server', message: fieldError.message })
    }
  }
  if (error.fieldErrors.length > 0 && unplaced.length === 0) return { banner: null, messages: [] }
  return { banner: error, messages: unplaced }
}

/** The problems a 422 from simulate lists in `errors`, as plain messages. */
export function scenarioProblemMessages(error: ApiError): string[] {
  const errors: unknown = error.body.errors
  if (!Array.isArray(errors)) return []
  const messages: string[] = []
  for (const entry of errors as unknown[]) {
    if (typeof entry === 'object' && entry !== null && 'message' in entry) {
      const message: unknown = entry.message
      if (typeof message === 'string') messages.push(message)
    }
  }
  return messages
}

/** A name for the baseline created for a scenario, within the 120 characters a name may have. */
export function baselineNameFor(scenarioName: string): string {
  const shown = scenarioName.length > 100 ? `${scenarioName.slice(0, 99)}…` : scenarioName
  return `Baseline for "${shown}"`
}
