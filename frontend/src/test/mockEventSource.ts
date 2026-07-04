type Listener = (e: { data: string }) => void;

/** 测试用 EventSource 替身：记录实例，供测试侧手动 emit 命名事件与连接错误。 */
export class MockEventSource {
  static instances: MockEventSource[] = [];
  url: string;
  closed = false;
  onerror: ((e: unknown) => void) | null = null;
  private listeners: Record<string, Listener[]> = {};

  constructor(url: string) {
    this.url = url;
    MockEventSource.instances.push(this);
  }

  addEventListener(type: string, cb: Listener) {
    (this.listeners[type] ||= []).push(cb);
  }

  close() { this.closed = true; }

  /** 测试侧：派发一个命名事件，data 为 JSON 序列化对象。 */
  emit(type: string, data: unknown) {
    (this.listeners[type] || []).forEach((cb) => cb({ data: JSON.stringify(data) }));
  }

  /** 测试侧：触发连接错误（对应原生 onerror）。 */
  emitError() { this.onerror?.({}); }

  static reset() { MockEventSource.instances = []; }
  static last(): MockEventSource { return MockEventSource.instances[MockEventSource.instances.length - 1]; }
}

export function installMockEventSource() {
  MockEventSource.reset();
  (globalThis as unknown as { EventSource: unknown }).EventSource = MockEventSource;
}
