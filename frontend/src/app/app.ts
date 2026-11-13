import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { AuthService } from './auth/auth.service';
import { AppNav } from './shell/app-nav';
import { Icon } from './shell/icon';
import { UserMenu } from './shell/user-menu';
import { WorkspaceContext } from './workspaces/workspace-context.service';
import { WorkspaceSwitcher } from './workspaces/workspace-switcher';

/**
 * App shell: top bar (logo, workspace switcher with role, user menu), a sidebar with the workspace's
 * sections and, for admins only, the admin page. Below the xl breakpoint (1280px) the sidebar moves
 * into a drawer opened from the menu button, so pages keep the full width they were designed for
 * (the chat needs three columns). The drawer is modal: focus moves in, Tab stays in, Escape closes.
 */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, WorkspaceSwitcher, AppNav, UserMenu, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './app.html',
  host: { '(document:keydown.escape)': 'closeMenu(true)' },
})
export class App {
  protected readonly auth = inject(AuthService);
  protected readonly context = inject(WorkspaceContext);

  protected readonly menuOpen = signal(false);
  private readonly menuButton = viewChild<ElementRef<HTMLButtonElement>>('menuButton');
  private readonly closeButton = viewChild<ElementRef<HTMLButtonElement>>('closeButton');
  private readonly drawer = viewChild<ElementRef<HTMLElement>>('drawer');

  constructor() {
    // Load the workspace list once signed in; forget it on sign-out.
    effect(() => {
      if (this.auth.isAuthenticated()) {
        if (!this.context.loaded()) {
          this.context.load().catch(() => undefined);
        }
      } else {
        this.context.clear();
      }
    });
    // Move focus into the drawer when it opens.
    effect(() => {
      if (this.menuOpen()) {
        this.closeButton()?.nativeElement.focus();
      }
    });
    // Any navigation (also the browser's back button) closes the drawer.
    const subscription = inject(Router)
      .events.pipe(filter((e) => e instanceof NavigationEnd))
      .subscribe(() => this.closeMenu());
    inject(DestroyRef).onDestroy(() => subscription.unsubscribe());
  }

  protected openMenu(): void {
    this.menuOpen.set(true);
  }

  /** @param restoreFocus return focus to the menu button (Escape, close button) */
  protected closeMenu(restoreFocus = false): void {
    if (!this.menuOpen()) {
      return;
    }
    this.menuOpen.set(false);
    if (restoreFocus) {
      this.menuButton()?.nativeElement.focus();
    }
  }

  /** Keeps Tab inside the open drawer (it is modal). */
  protected onDrawerKeydown(event: KeyboardEvent): void {
    if (event.key !== 'Tab') {
      return;
    }
    const focusable = Array.from(
      this.drawer()?.nativeElement.querySelectorAll<HTMLElement>(
        'a[href], button:not([disabled]), select:not([disabled])',
      ) ?? [],
    );
    if (!focusable.length) {
      return;
    }
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }
}
