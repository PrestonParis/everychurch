import { Component, inject, signal } from '@angular/core';
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
    password: ['', [Validators.required, Validators.minLength(8)]],
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

  toggleShowPassword() {
    this._showPassword.update((v) => !v);
  }

  submit() {
    if (this.form.invalid) return;
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
        this._error.set(err.error?.error ?? 'Something went wrong. Please try again.');
      },
    });
  }
}
