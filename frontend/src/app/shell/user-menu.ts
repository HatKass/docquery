import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  inject,
  signal,
  viewChild,
  viewChildren,
} from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { WorkspaceContext } from '../workspaces/workspace-context.service';
import { Icon } from './icon';

/** "Olivia Owner" → "OO", "max" → "M". */
export function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (!parts.length) {
    return '?';
  }
  const letters = parts.length === 1 ? parts[0][0] : parts[0][0] + parts[parts.length - 1][0];
  return letters.toUpperCase();
}

/**
 * Avatar button with a dropdown (WAI-ARIA menu button pattern): opens on click, Enter, Space or
 * ArrowDown (focus on the first item) and ArrowUp (last item); arrows, Home and End move between
 * items; Escape closes and returns focus to the button; Tab or a click outside closes.
 */
@Component({
  selector: 'app-user-menu',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'relative',
    '(document:click)': 'onDocumentClick($event)',
  },
  template: `
    <button
      #trigger
      type="button"
      id="user-menu-button"
      class="flex items-center gap-2 rounded-full py-1 pl-1 pr-2 text-sm text-slate-700 hover:bg-slate-100 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
      aria-haspopup="menu"
      aria-controls="user-menu"
      [attr.aria-expanded]="open()"
      [attr.aria-label]="'Account menu for ' + name()"
      data-testid="user-menu-button"
      (click)="toggle()"
      (keydown)="onTriggerKeydown($event)"
    >
      <span
        class="flex size-8 items-center justify-center rounded-full bg-indigo-600 text-xs font-semibold text-white"
        data-testid="avatar"
        >{{ avatar() }}</span
      >
      <span class="hidden max-w-40 truncate font-medium sm:inline" data-testid="user-name">{{
        name()
      }}</span>
      <!-- The wrapper owns visibility: app-icon's own inline-flex would override "hidden". -->
      <span class="hidden sm:inline-flex"
        ><app-icon name="chevron-down" class="size-4 text-slate-400"
      /></span>
    </button>

    @if (open()) {
      <div
        id="user-menu"
        role="menu"
        aria-labelledby="user-menu-button"
        class="absolute right-0 z-40 mt-2 w-60 origin-top-right rounded-lg border border-slate-200 bg-white py-1 shadow-lg focus:outline-none"
        data-testid="user-menu"
        (keydown)="onMenuKeydown($event)"
      >
        <div class="border-b border-slate-100 px-4 py-3" role="none">
          <p class="truncate text-sm font-medium text-slate-900">{{ name() }}</p>
          @if (username(); as u) {
            <p class="truncate text-xs text-slate-500">{{ '@' + u }}</p>
          }
        </div>
        <div class="py-1" role="none">
          <button
            #item
            type="button"
            role="menuitem"
            tabindex="-1"
            [class]="itemClass"
            (click)="manageWorkspaces()"
          >
            <app-icon name="folders" class="size-4 text-slate-400" />
            Manage workspaces
          </button>
          <button
            #item
            type="button"
            role="menuitem"
            tabindex="-1"
            [class]="itemClass"
            data-testid="sign-out"
            (click)="signOut()"
          >
            <app-icon name="logout" class="size-4 text-slate-400" />
            Sign out
          </button>
        </div>
      </div>
    }
  `,
})
export class UserMenu {
  private readonly auth = inject(AuthService);
  private readonly context = inject(WorkspaceContext);
  private readonly router = inject(Router);
  private readonly host = inject(ElementRef<HTMLElement>);

  private readonly trigger = viewChild.required<ElementRef<HTMLButtonElement>>('trigger');
  private readonly items = viewChildren<ElementRef<HTMLButtonElement>>('item');

  protected readonly open = signal(false);
  protected readonly name = computed(
    () => this.context.me()?.displayName ?? this.auth.displayName(),
  );
  protected readonly username = computed(() => this.context.me()?.username ?? null);
  protected readonly avatar = computed(() => initials(this.name()));
  protected readonly itemClass =
    'flex w-full items-center gap-3 px-4 py-2 text-left text-sm text-slate-700 hover:bg-slate-50 focus:bg-slate-100 focus:outline-none';

  protected toggle(): void {
    if (this.open()) {
      this.close(true);
    } else {
      this.show(0);
    }
  }

  protected onTriggerKeydown(event: KeyboardEvent): void {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      this.show(event.key === 'ArrowDown' ? 0 : -1);
    } else if (event.key === 'Escape' && this.open()) {
      event.preventDefault();
      this.close(true);
    }
    // Enter and Space fire a click on the button, which toggles the menu.
  }

  protected onMenuKeydown(event: KeyboardEvent): void {
    const items = this.items().map((i) => i.nativeElement);
    const current = items.indexOf(document.activeElement as HTMLButtonElement);
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        items[(current + 1) % items.length]?.focus();
        break;
      case 'ArrowUp':
        event.preventDefault();
        items[(current - 1 + items.length) % items.length]?.focus();
        break;
      case 'Home':
        event.preventDefault();
        items[0]?.focus();
        break;
      case 'End':
        event.preventDefault();
        items[items.length - 1]?.focus();
        break;
      case 'Escape':
        event.preventDefault();
        this.close(true);
        break;
      case 'Tab':
        this.close(false);
        break;
    }
  }

  protected onDocumentClick(event: MouseEvent): void {
    if (this.open() && !this.host.nativeElement.contains(event.target as Node)) {
      this.close(false);
    }
  }

  protected manageWorkspaces(): void {
    this.close(true);
    void this.router.navigate(['/workspaces']);
  }

  protected signOut(): void {
    this.close(false);
    void this.auth.logout();
  }

  /** @param focusIndex item to focus; -1 for the last */
  private show(focusIndex: number): void {
    this.open.set(true);
    // The menu renders on the next change detection; focus once it exists.
    setTimeout(() => {
      const items = this.items();
      items.at(focusIndex)?.nativeElement.focus();
    });
  }

  private close(restoreFocus: boolean): void {
    this.open.set(false);
    if (restoreFocus) {
      this.trigger().nativeElement.focus();
    }
  }
}
