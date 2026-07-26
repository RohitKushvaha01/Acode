/// <reference path="./lang/index.d.ts" />
/// <reference path="../node_modules/html-tag-js/index.d.ts" />

declare const ASSETS_DIRECTORY: string;
declare const DATA_STORAGE: string;
declare const CACHE_STORAGE: string;
declare const PLUGIN_DIR: string;
declare const KEYBINDING_FILE: string;
declare const ANDROID_SDK_INT: number;
declare const DOES_SUPPORT_THEME: boolean;
declare const acode: {
  webview: AcodeWebViewAPI;
  [key: string]: unknown;
};

interface Window {
  ASSETS_DIRECTORY: string;
  DATA_STORAGE: string;
  CACHE_STORAGE: string;
  PLUGIN_DIR: string;
  KEYBINDING_FILE: string;
  ANDROID_SDK_INT: number;
  DOES_SUPPORT_THEME: boolean;
  acode: object;
}

interface String {
  /**
   * Capitalize the first letter of a string
   */
  capitalize(): string;
  /**
   * Generate a hash from a string
   */
  hashCode(): string;
}

type ExecutorCallback = (
  type: "stdout" | "stderr" | "exit",
  data: string,
) => void;

interface Executor {
  execute: (command: string, alpine: boolean) => Promise<string>;
  start: (
    command: string,
    callback: ExecutorCallback,
    alpine: boolean,
  ) => Promise<string>;
  write: (uuid: string, input: string) => Promise<void>;
  stop: (uuid: string) => Promise<void>;
  isRunning: (uuid: string) => Promise<boolean>;
  listProcesses: () => Promise<ExecutorProcess[]>;
  /** Move the executor service to the foreground (shows notification) */
  moveToForeground: () => Promise<void>;
  /** Move the executor service to the background (hides notification) */
  moveToBackground: () => Promise<void>;
  /** Stop the executor service completely */
  stopService: () => Promise<void>;
  /**
   * Background executor
   */
  BackgroundExecutor: Executor;
}

interface ExecutorProcess {
  id: string;
  pid: number;
  command: string;
  alpine: boolean;
  startedAt: number;
  background: boolean;
}

declare const Executor: Executor | undefined;

interface Window {
  Executor?: Executor;
  editorManager?: EditorManager;
}

interface EditorManager {
  editor?: import("@codemirror/view").EditorView;
  isCodeMirror?: boolean;
  activeFile?: AcodeFile;
  getLspMetadata?: (file: AcodeFile) => LspFileMetadata | null;
}

interface LspFileMetadata {
  uri: string;
  languageId?: string;
  languageName?: string;
  view?: import("@codemirror/view").EditorView;
  file?: AcodeFile;
  rootUri?: string;
}

/**
 * Acode file object
 */
interface AcodeFile {
  uri?: string;
  name?: string;
  session?: unknown;
  cacheFile?: string;
  [key: string]: unknown;
}

// Extend globalThis with Executor
declare global {
  var Executor: Executor | undefined;
}

interface WebViewOptions {
  /** Title applied to the hosting activity in fullscreen mode. */
  title?: string;
  /** Display mode. Defaults to "hidden". */
  mode?: "fullscreen" | "window" | "panel" | "hidden";
  /** Width in dp ("window" mode). */
  width?: number;
  /** Height in dp ("window"/"panel" modes). */
  height?: number;
  /** Left offset in dp ("window" mode, centered when unset). */
  x?: number;
  /** Top offset in dp ("window" mode, centered when unset). */
  y?: number;
  /**
   * Allow in-WebView navigation. Defaults to true. Only http(s) targets
   * ever load; other schemes are always blocked for isolation.
   */
  allowNavigation?: boolean;
  /** Ask the user before downloading files. Defaults to false. */
  allowDownloads?: boolean;
  /**
   * Show immediately after creation. Defaults to true. When false, window
   * and panel instances stay detached and fullscreen instances defer their
   * activity launch until show() is called.
   */
  visible?: boolean;
}

interface AcodeWebView {
  readonly id: string;
  readonly options: WebViewOptions;
  /** Load an http(s) URL. Other schemes are rejected. */
  loadURL(url: string): Promise<void>;
  loadHTML(html: string): Promise<void>;
  evaluate(js: string): Promise<string>;
  onMessage(callback: (message: unknown) => void): void;
  offMessage(callback: (message: unknown) => void): void;
  /**
   * Subscribe to lifecycle events: "pageFinished", "titleChanged",
   * "dismissed" (backdrop tap in "window" mode) and "closed" (fullscreen
   * closed by the user or by hide()). After "closed" the instance is
   * destroyed and cannot be reused.
   */
  on(event: string, callback: (event: string, data?: unknown) => void): void;
  off(event: string, callback: (event: string, data?: unknown) => void): void;
  postMessage(message: unknown): Promise<void>;
  show(): Promise<void>;
  /**
   * Hide the WebView. In fullscreen mode this closes the hosting activity,
   * which destroys the instance and emits "closed".
   */
  hide(): Promise<void>;
  reload(): Promise<void>;
  destroy(): Promise<void>;
}

interface AcodeWebViewAPI {
  create(options?: WebViewOptions): Promise<AcodeWebView>;
}
