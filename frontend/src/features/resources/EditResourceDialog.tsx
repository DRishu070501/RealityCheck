import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { RotateCw, TriangleAlert } from 'lucide-react'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'

import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogBody,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Field, Input, Select } from '@/components/ui/form'
import { api, ApiError } from '@/lib/api'
import { queryKeys } from '@/lib/queryKeys'
import { resourceStatusSchema, type Resource } from '@/lib/schemas'
import { describedBy } from '@/lib/utils'

/** Mirrors the constraints of `UpdateResourceRequest` on the backend. */
const formSchema = z.object({
  name: z.string().trim().min(1, 'Enter a name').max(120, 'At most 120 characters'),
  category: z
    .string()
    .regex(/^[A-Z][A-Z0-9_]{1,59}$/, 'Use upper-case letters, digits and underscores, for example POLISHING_WHEEL'),
  capacity: z
    .number({ error: 'Enter a whole number of units' })
    .int('Enter a whole number of units')
    .min(0, 'Capacity cannot be negative')
    .max(100_000, 'At most 100,000 units'),
  hourlyCost: z
    .string()
    .trim()
    .regex(/^\d{1,10}(\.\d{1,2})?$/, 'Enter an amount of zero or more with at most two decimals, for example 140.00'),
  status: resourceStatusSchema,
})
type FormValues = z.infer<typeof formSchema>
const FIELDS: readonly (keyof FormValues)[] = ['name', 'category', 'capacity', 'hourlyCost', 'status']

function valuesOf(resource: Resource): FormValues {
  return {
    name: resource.name,
    category: resource.category,
    capacity: resource.capacity,
    hourlyCost: resource.hourlyCost,
    status: resource.status,
  }
}

/** The backend answers every conflict with 409; a stale version is told apart by its wording. */
function isStaleVersion(error: ApiError): boolean {
  return error.status === 409 && error.code === 'STALE_VERSION'
}

interface EditResourceDialogProps {
  resource: Resource
  currencyCode: string
  onClose: () => void
}

export function EditResourceDialog({ resource, currencyCode, onClose }: EditResourceDialogProps) {
  const queryClient = useQueryClient()
  // The copy being edited; replaced with the latest from the backend after a stale-version conflict.
  const [base, setBase] = useState(resource)
  const [failure, setFailure] = useState<ApiError | null>(null)
  const [reloading, setReloading] = useState(false)

  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { errors },
  } = useForm<FormValues>({ resolver: zodResolver(formSchema), defaultValues: valuesOf(resource) })

  const update = useMutation({
    mutationFn: (values: FormValues) => api.resources.update(base.id, { ...values, version: base.version }),
    onSuccess: async () => {
      // Stage slot capacity is derived from pool capacity, so workflows are stale too.
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.resources.all }),
        queryClient.invalidateQueries({ queryKey: queryKeys.workflows.all }),
        queryClient.invalidateQueries({ queryKey: queryKeys.factories.all }),
      ])
      onClose()
    },
    onError: (error) => {
      if (!(error instanceof ApiError)) {
        setFailure(new ApiError({ kind: 'network', title: 'The pool could not be saved' }))
        return
      }
      const unmatched = error.fieldErrors.filter((fieldError) => {
        const field = FIELDS.find((name) => name === fieldError.field)
        if (!field) return true
        setError(field, { type: 'server', message: fieldError.message })
        return false
      })
      // Field errors are shown on their fields; anything else needs the banner.
      setFailure(error.fieldErrors.length > 0 && unmatched.length === 0 ? null : error)
    },
  })

  const reloadLatest = async () => {
    setReloading(true)
    try {
      const latest = await api.resources.get(base.id)
      setBase(latest)
      reset(valuesOf(latest))
      setFailure(null)
    } catch (error) {
      setFailure(error instanceof ApiError ? error : new ApiError({ kind: 'network', title: 'Reload failed' }))
    } finally {
      setReloading(false)
    }
  }

  const submit = handleSubmit((values) => {
    setFailure(null)
    update.mutate(values)
  })

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
    >
      <DialogContent>
        <form onSubmit={(event) => void submit(event)} noValidate className="flex min-h-0 flex-col">
          <DialogHeader>
            <DialogTitle>Edit {base.name}</DialogTitle>
            <DialogDescription>
              {base.kind === 'MACHINE' ? 'Machine pool' : 'Employee pool'}. The kind of a pool cannot be changed.
            </DialogDescription>
          </DialogHeader>

          <DialogBody className="flex flex-col gap-4">
            {failure ? (
              <div
                role="alert"
                className="flex flex-col gap-2 rounded-md border border-status-error/30 bg-status-error-surface px-3 py-2.5"
              >
                <div className="flex items-start gap-2">
                  <TriangleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-status-error" />
                  <div className="min-w-0 text-sm">
                    <p className="font-medium text-foreground">
                      {isStaleVersion(failure) ? 'This pool was changed after you opened it' : failure.title}
                    </p>
                    <p className="text-muted-foreground">
                      {isStaleVersion(failure)
                        ? 'Your changes were not saved. Reload the latest values, then make your change again.'
                        : (failure.detail ?? 'The request could not be completed.')}
                    </p>
                  </div>
                </div>
                {isStaleVersion(failure) ? (
                  <Button
                    variant="outline"
                    size="sm"
                    className="self-start"
                    onClick={() => void reloadLatest()}
                    disabled={reloading}
                  >
                    <RotateCw aria-hidden="true" className={reloading ? 'animate-spin' : undefined} />
                    Reload latest values
                  </Button>
                ) : null}
              </div>
            ) : null}

            <Field htmlFor="resource-name" label="Name" error={errors.name?.message}>
              <Input
                id="resource-name"
                autoComplete="off"
                aria-invalid={errors.name ? true : undefined}
                aria-describedby={describedBy('resource-name', false, Boolean(errors.name))}
                {...register('name')}
              />
            </Field>

            <Field
              htmlFor="resource-category"
              label="Category"
              hint="Machine type or skill, as an upper-case code."
              error={errors.category?.message}
            >
              <Input
                id="resource-category"
                autoComplete="off"
                aria-invalid={errors.category ? true : undefined}
                aria-describedby={describedBy('resource-category', true, Boolean(errors.category))}
                {...register('category')}
              />
            </Field>

            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <Field
                htmlFor="resource-capacity"
                label="Capacity (units)"
                hint="Interchangeable units in the pool."
                error={errors.capacity?.message}
              >
                <Input
                  id="resource-capacity"
                  type="number"
                  inputMode="numeric"
                  min={0}
                  step={1}
                  aria-invalid={errors.capacity ? true : undefined}
                  aria-describedby={describedBy('resource-capacity', true, Boolean(errors.capacity))}
                  {...register('capacity', { valueAsNumber: true })}
                />
              </Field>

              <Field
                htmlFor="resource-cost"
                label={`Hourly cost (${currencyCode})`}
                hint="Per unit, per hour."
                error={errors.hourlyCost?.message}
              >
                <Input
                  id="resource-cost"
                  inputMode="decimal"
                  autoComplete="off"
                  aria-invalid={errors.hourlyCost ? true : undefined}
                  aria-describedby={describedBy('resource-cost', true, Boolean(errors.hourlyCost))}
                  {...register('hourlyCost')}
                />
              </Field>
            </div>

            <Field
              htmlFor="resource-status"
              label="Status"
              hint="A pool the active workflow requires cannot be made inactive."
              error={errors.status?.message}
            >
              <Select
                id="resource-status"
                aria-invalid={errors.status ? true : undefined}
                aria-describedby={describedBy('resource-status', true, Boolean(errors.status))}
                {...register('status')}
              >
                <option value="ACTIVE">Active</option>
                <option value="INACTIVE">Inactive</option>
              </Select>
            </Field>
          </DialogBody>

          <DialogFooter>
            <DialogClose asChild>
              <Button variant="outline">Cancel</Button>
            </DialogClose>
            <Button type="submit" disabled={update.isPending || reloading}>
              {update.isPending ? 'Saving' : 'Save changes'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
