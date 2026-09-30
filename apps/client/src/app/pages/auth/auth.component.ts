import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { AuthService } from '../../services/auth.service';

@Component({
  selector: 'app-auth',
  standalone: true,
  imports: [RouterModule, ReactiveFormsModule],
  templateUrl: './auth.component.html',
})
export class AuthComponent {
  private fb = inject(FormBuilder);
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  private authService = inject(AuthService);

  mode: 'login' | 'register' = this.route.snapshot.data['mode'] === 'register' ? 'register' : 'login';

  form = this.fb.group({
    email: ['', [Validators.required, Validators.email]],
    // The 8-character minimum only applies to choosing a password — enforcing it on login
    // would silently disable the button for anyone whose existing password is shorter.
    password: ['', this.mode === 'register' ? [Validators.required, Validators.minLength(8)] : [Validators.required]],
    // Only required in register mode — no point asking for a name to log in with an
    // existing account. Login mode leaves the control unvalidated and unrendered.
    displayName: ['', this.mode === 'register' ? [Validators.required] : []],
  });

  // Signal-backed so an HTTP callback re-renders the page (the app is zoneless — a plain
  // field assigned in a callback never repaints, so a failed login would show no error).
  private readonly _submitting = signal(false);
  private readonly _error = signal('');
  private readonly _showPassword = signal(false);
  get submitting(): boolean { return this._submitting(); }
  get error(): string { return this._error(); }
  get showPassword(): boolean { return this._showPassword(); }

  constructor() {
    // A stale error ("Incorrect email or password") shouldn't linger once the user starts fixing it.
    this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => {
      if (this._error()) this._error.set('');
    });
  }

  /** True once a field has been left (or submitted) in an invalid state — drives the inline hints. */
  showInvalid(name: 'email' | 'password' | 'displayName'): boolean {
    const control = this.form.controls[name];
    return control.invalid && control.touched;
  }

  toggleShowPassword() {
    this._showPassword.update((v) => !v);
  }

  submit() {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this._submitting.set(true);
    this._error.set('');

    const { email, password, displayName } = this.form.getRawValue();
    const obs =
      this.mode === 'register'
        ? this.authService.register(email!, password!, displayName!)
        : this.authService.login(email!, password!);

    obs.subscribe({
      next: () => this.router.navigate(['/churches']),
      error: (err) => {
        this._submitting.set(false);
        this._error.set(this.describeError(err));
      },
    });
  }

  /** Turns a failed request into something the user can act on — never a bare "something went wrong"
   *  when we know more (e.g. a network failure vs. wrong credentials vs. the server being down). */
  private describeError(err: HttpErrorResponse): string {
    if (err.status === 401 && this.mode === 'login') {
      return 'Incorrect email or password.';
    }
    const serverMessage = typeof err.error?.error === 'string' ? err.error.error : null;
    if (serverMessage) return serverMessage;
    if (err.status === 0) {
      return "Couldn't reach Every Church. Check your internet connection and try again.";
    }
    if (err.status === 429) {
      return 'Too many attempts. Please wait a minute and try again.';
    }
    if (err.status >= 500) {
      return `Our server ran into a problem (error ${err.status}). Please try again in a moment.`;
    }
    return `Something went wrong (error ${err.status}). Please try again.`;
  }
}
