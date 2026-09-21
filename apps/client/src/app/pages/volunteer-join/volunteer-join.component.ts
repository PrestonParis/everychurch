import { Component, inject } from '@angular/core';
import { Router, RouterModule } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { AuthService } from '../../services/auth.service';

@Component({
  selector: 'app-volunteer-join',
  standalone: true,
  imports: [RouterModule, ReactiveFormsModule],
  templateUrl: './volunteer-join.component.html',
})
export class VolunteerJoinComponent {
  private fb = inject(FormBuilder);
  private router = inject(Router);
  private authService = inject(AuthService);

  form = this.fb.group({
    code: ['', Validators.required],
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(8)]],
  });

  submitting = false;
  error = '';

  submit() {
    if (this.form.invalid) return;
    this.submitting = true;
    this.error = '';

    const { code, email, password } = this.form.getRawValue();
    this.authService.registerVolunteer(code!, email!, password!).subscribe({
      next: () => this.router.navigate(['/churches']),
      error: (err) => {
        this.submitting = false;
        this.error = err.error?.error ?? 'Could not redeem that invite. Check the code and try again.';
      },
    });
  }
}
