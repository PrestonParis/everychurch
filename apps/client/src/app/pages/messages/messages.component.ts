import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterModule } from '@angular/router';
import { ConversationSummary } from '../../models/direct-message.model';
import { DirectMessageService } from '../../services/direct-message.service';

@Component({
  selector: 'app-messages',
  standalone: true,
  imports: [RouterModule],
  templateUrl: './messages.component.html',
})
export class MessagesComponent implements OnInit {
  private directMessageService = inject(DirectMessageService);

  conversations = signal<ConversationSummary[]>([]);
  loading = signal(true);
  error = signal(false);

  ngOnInit() {
    this.directMessageService.conversations().subscribe({
      next: (conversations) => {
        this.conversations.set(conversations);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
    });
  }

  formatTime(iso: string): string {
    const date = new Date(iso);
    return date.toDateString() === new Date().toDateString()
      ? date.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })
      : date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
  }
}
