/** Test helpers for the confirmation dialog, which ConfirmDialog appends to document.body. */
export const confirmDialog = {
  element: () => document.querySelector<HTMLDialogElement>('[data-testid="confirm-dialog"]'),
  variant: () => confirmDialog.element()?.dataset['variant'],
  title: () => confirmDialog.element()?.querySelector('[data-testid="confirm-title"]')?.textContent?.trim(),
  message: () => confirmDialog.element()?.querySelector('[data-testid="confirm-message"]')?.textContent?.trim(),
  error: () => confirmDialog.element()?.querySelector('[data-testid="confirm-error"]')?.textContent?.trim(),
  accept: () => confirmDialog.element()!.querySelector<HTMLButtonElement>('[data-testid="confirm-accept"]')!,
  cancel: () => confirmDialog.element()!.querySelector<HTMLButtonElement>('[data-testid="confirm-cancel"]')!,
  pressEscape: () =>
    confirmDialog
      .element()!
      .dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })),
  /** Removes leftovers between tests. */
  cleanUp: () => document.querySelectorAll('app-confirm-dialog').forEach((e) => e.remove()),
};
