import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { ConfirmDialog, ConfirmOptions } from './confirm-dialog';
import { confirmDialog } from './testing/confirm-dialog.harness';

const settle = () => new Promise((r) => setTimeout(r));

describe('ConfirmDialog', () => {
  let service: ConfirmDialog;
  let opener: HTMLButtonElement;

  beforeEach(() => {
    service = TestBed.inject(ConfirmDialog);
    opener = document.createElement('button');
    opener.textContent = 'Delete';
    document.body.appendChild(opener);
    opener.focus();
  });

  afterEach(() => {
    opener.remove();
    confirmDialog.cleanUp();
  });

  const danger: ConfirmOptions = {
    title: 'Delete CIN.pdf?',
    message: 'The file, its text chunks and embeddings will be permanently removed.',
    variant: 'danger',
    confirmLabel: 'Delete document',
    busyLabel: 'Deleting…',
  };

  it('shows an accessible modal with title, message and labelled buttons', async () => {
    void service.open(danger);
    await settle();

    const dialog = confirmDialog.element()!;
    expect(dialog.open).toBe(true);
    expect(confirmDialog.title()).toBe('Delete CIN.pdf?');
    expect(confirmDialog.message()).toContain('permanently removed');
    expect(document.getElementById(dialog.getAttribute('aria-labelledby')!)?.textContent).toContain('Delete CIN.pdf?');
    expect(document.getElementById(dialog.getAttribute('aria-describedby')!)?.textContent).toContain('embeddings');
    expect(confirmDialog.accept().textContent?.trim()).toBe('Delete document');
    expect(confirmDialog.cancel().textContent?.trim()).toBe('Cancel');
  });

  it('uses a red confirm button and focuses Cancel first for danger actions', async () => {
    void service.open(danger);
    await settle();

    expect(confirmDialog.element()!.dataset['variant']).toBe('danger');
    expect(confirmDialog.accept().className).toContain('bg-red-600');
    expect(document.activeElement).toBe(confirmDialog.cancel());
  });

  it('uses the default style and focuses Confirm for normal actions', async () => {
    void service.open({ title: 'Change role?', message: 'Details.' });
    await settle();

    expect(confirmDialog.accept().className).toContain('bg-indigo-600');
    expect(confirmDialog.accept().className).not.toContain('bg-red-600');
    expect(document.activeElement).toBe(confirmDialog.accept());
  });

  it('keeps Tab and Shift+Tab inside the dialog', async () => {
    void service.open(danger);
    await settle();
    const tab = (shiftKey = false) =>
      confirmDialog.element()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', shiftKey, bubbles: true, cancelable: true }));

    confirmDialog.accept().focus();
    tab();
    expect(document.activeElement).toBe(confirmDialog.cancel());
    tab(true);
    expect(document.activeElement).toBe(confirmDialog.accept());
  });

  it('resolves false on Cancel, removes the dialog and returns focus to the opener', async () => {
    const result = service.open(danger);
    await settle();

    confirmDialog.cancel().click();

    expect(await result).toBe(false);
    expect(confirmDialog.element()).toBeNull();
    expect(document.activeElement).toBe(opener);
  });

  it('resolves false on Escape and returns focus to the opener', async () => {
    const result = service.open(danger);
    await settle();

    confirmDialog.pressEscape();

    expect(await result).toBe(false);
    expect(confirmDialog.element()).toBeNull();
    expect(document.activeElement).toBe(opener);
  });

  it('shows a busy confirm button while the action runs, then resolves true', async () => {
    let finish!: () => void;
    const action = vi.fn(() => new Promise<void>((resolve) => (finish = resolve)));
    const result = service.open({ ...danger, action });
    await settle();

    confirmDialog.accept().click();
    await settle();

    expect(action).toHaveBeenCalledTimes(1);
    expect(confirmDialog.accept().disabled).toBe(true);
    expect(confirmDialog.accept().getAttribute('aria-busy')).toBe('true');
    expect(confirmDialog.accept().textContent).toContain('Deleting…');
    expect(confirmDialog.cancel().disabled).toBe(true);
    // Escape is ignored while the request runs.
    confirmDialog.pressEscape();
    expect(confirmDialog.element()).not.toBeNull();

    finish();
    expect(await result).toBe(true);
    expect(confirmDialog.element()).toBeNull();
    expect(document.activeElement).toBe(opener);
  });

  it('shows a failure inside the dialog, stays open and allows a retry', async () => {
    const action = vi
      .fn()
      .mockRejectedValueOnce(
        new HttpErrorResponse({ status: 409, error: { detail: 'A workspace needs at least one owner.' } }),
      )
      .mockResolvedValueOnce(undefined);
    const result = service.open({ ...danger, action });
    await settle();

    confirmDialog.accept().click();
    await settle();

    expect(confirmDialog.element()).not.toBeNull();
    expect(confirmDialog.error()).toContain('at least one owner');
    expect(confirmDialog.element()!.querySelector('[role="alert"]')).not.toBeNull();
    expect(confirmDialog.accept().disabled).toBe(false);

    confirmDialog.accept().click();
    expect(await result).toBe(true);
    expect(action).toHaveBeenCalledTimes(2);
  });

  it('can be cancelled after a failure', async () => {
    const result = service.open({ ...danger, action: () => Promise.reject(new Error('boom')) });
    await settle();
    confirmDialog.accept().click();
    await settle();

    confirmDialog.cancel().click();

    expect(await result).toBe(false);
  });
});
