import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  effect,
  inject,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { AuthService } from './auth/auth.service';
import { SessionWatch } from './auth/session-watch.service';
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
  host: {
    '(document:keydown.escape)': 'closeMenu(true)',
    '(window:pageshow)': 'onPageShow($event)',
  },
})
export class App {
  protected readonly auth = inject(AuthService);
  protected readonly context = inject(WorkspaceContext);
  private readonly session = inject(SessionWatch);

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
        // Session ended while on a protected page (token expired, 401): go to the signed-out home.
        untracked(() => void this.session.leaveIfSignedOut());
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

  /**
   * Back/Forward can restore this page from the browser's back-forward cache, frozen as it was when
   * the user left (e.g. mid sign-out). Guards do not run again then, so re-check the session here.
   */
  protected onPageShow(event: PageTransitionEvent): void {
    if (event.persisted) {
      this.closeMenu();
      void this.session.onPageRestored();
    }
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
