import type { SimulatedEvent, StageResult } from '@/lib/scenarioSchemas'

export interface Bar {
  batchId: string
  start: number
  end: number
}

export interface Lane {
  stage: StageResult
  bars: Bar[]
}

/** Pairs each job's start with its completion, per stage. A start without a completion runs to the end. */
export function buildLanes(events: readonly SimulatedEvent[], stages: readonly StageResult[]): Lane[] {
  const lastTime = events.reduce((max, event) => Math.max(max, Date.parse(event.occurredAt)), 0)
  return stages.map((stage) => {
    const open = new Map<string, number>()
    const bars: Bar[] = []
    for (const event of events) {
      if (event.stageId !== stage.stageId || event.batchId === null) continue
      const time = Date.parse(event.occurredAt)
      if (event.eventType === 'JOB_STARTED') open.set(event.batchId, time)
      if (event.eventType === 'JOB_COMPLETED') {
        const start = open.get(event.batchId)
        bars.push({ batchId: event.batchId, start: start ?? time, end: time })
        open.delete(event.batchId)
      }
    }
    for (const [batchId, start] of open) bars.push({ batchId, start, end: lastTime })
    return { stage, bars }
  })
}
