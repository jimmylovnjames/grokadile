export interface Logger {
  info(message: string, meta?: Record<string, unknown>): void;
  warn(message: string, meta?: Record<string, unknown>): void;
  error(message: string, meta?: Record<string, unknown>): void;
}

/** Structured JSON-line logger; pipe stdout to your log sink of choice. */
export class ConsoleLogger implements Logger {
  constructor(private readonly base: Record<string, unknown> = {}) {}

  private emit(level: string, message: string, meta?: Record<string, unknown>): void {
    // eslint-disable-next-line no-console
    console.log(JSON.stringify({ level, message, ts: Date.now(), ...this.base, ...meta }));
  }

  info(message: string, meta?: Record<string, unknown>): void {
    this.emit('info', message, meta);
  }
  warn(message: string, meta?: Record<string, unknown>): void {
    this.emit('warn', message, meta);
  }
  error(message: string, meta?: Record<string, unknown>): void {
    this.emit('error', message, meta);
  }
}

/** Silent logger for tests. */
export class NullLogger implements Logger {
  info(): void {}
  warn(): void {}
  error(): void {}
}
