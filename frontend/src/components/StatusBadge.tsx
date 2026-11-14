import { CircleAlert, CircleCheck, CircleHelp, CircleMinus } from 'lucide-react'

import { Badge, type BadgeVariant } from '@/components/ui/badge'

type Tone = 'ok' | 'error' | 'warn' | 'neutral'

const ICONS = {
  ok: CircleCheck,
  error: CircleAlert,
  warn: CircleHelp,
  neutral: CircleMinus,
} as const satisfies Record<Tone, unknown>

/** Maps a Spring Boot health status string to a visual tone. Unknown values are not treated as healthy. */
function toneForHealth(status: string): Tone {
  switch (status.toUpperCase()) {
    case 'UP':
      return 'ok'
    case 'DOWN':
    case 'OUT_OF_SERVICE':
      return 'error'
    default:
      return 'warn'
  }
}

interface StatusBadgeProps {
  /** Text shown in the badge. Status is never conveyed by colour alone. */
  label: string
  /** Explicit tone; when omitted it is derived from `label` as a health status. */
  tone?: Tone
}

export function StatusBadge({ label, tone }: StatusBadgeProps) {
  const resolved: Tone = tone ?? toneForHealth(label)
  const Icon = ICONS[resolved]
  const variant: BadgeVariant = resolved
  return (
    <Badge variant={variant}>
      <Icon aria-hidden="true" />
      {label}
    </Badge>
  )
}
