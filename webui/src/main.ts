// MobileGem chat web app.
//
// Renders the pi-web-ui ChatPanel and routes every LLM call to the Android
// app's embedded OpenAI-compatible server. Built output is bundled into the
// Android app's assets and loaded inside a WebView in a later task.

import { Agent } from "@mariozechner/pi-agent-core";
import { registerBuiltInApiProviders } from "@mariozechner/pi-ai";
import type { Model } from "@mariozechner/pi-ai";
import {
  AppStorage,
  ChatPanel,
  CustomProvidersStore,
  IndexedDBStorageBackend,
  ProviderKeysStore,
  SessionsStore,
  SettingsStore,
  defaultConvertToLlm,
  setAppStorage,
} from "@mariozechner/pi-web-ui";
import "@mariozechner/pi-web-ui/app.css";

// INVARIANT (a): the LLM provider base URL is exactly the local server.
//
// In the Android app this is the embedded server on 127.0.0.1:8765. During
// local browser development it can be pointed at any OpenAI-compatible server
// (e.g. an Ollama instance via the Vite dev proxy) by setting VITE_LLM_BASE_URL.
const LOCAL_BASE_URL =
  (import.meta as unknown as { env?: Record<string, string | undefined> }).env
    ?.VITE_LLM_BASE_URL ?? "http://127.0.0.1:8765/v1";

// Identifier for our single custom OpenAI-compatible provider.
const PROVIDER_ID = "mobilegem-local";

// The local server binds to 127.0.0.1 and requires no authentication, but
// pi-web-ui refuses to send a message unless a non-empty provider key exists
// for the active model's provider. We store this constant sentinel to satisfy
// that gate; the value is never transmitted anywhere meaningful.
const LOCAL_API_KEY = "local";

// Used when the local server cannot be reached during startup.
const FALLBACK_MODEL_ID = "gemma";

/**
 * Resolve the model id from the local server's OpenAI-style /models endpoint.
 * This is the single source of truth for which model the chat talks to — it is
 * whatever the user selected in the Android Settings screen and loaded into the
 * server. The user never picks a model inside the chat panel.
 */
async function resolveModelId(): Promise<string> {
  try {
    const response = await fetch(`${LOCAL_BASE_URL}/models`);
    if (!response.ok) {
      throw new Error(`models request failed: ${response.status}`);
    }
    const body = (await response.json()) as {
      object?: string;
      data?: Array<{ id?: string }>;
    };
    const id = body.data?.[0]?.id;
    if (typeof id === "string" && id.length > 0) {
      return id;
    }
    throw new Error("models response contained no usable id");
  } catch (err) {
    console.warn(`Falling back to "${FALLBACK_MODEL_ID}" model id:`, err);
    return FALLBACK_MODEL_ID;
  }
}

/**
 * Build a Model that points the openai-completions provider at the local
 * server. The pi-ai openai-completions provider uses model.baseUrl directly
 * as the OpenAI client base URL.
 */
function buildLocalModel(modelId: string): Model<"openai-completions"> {
  return {
    id: modelId,
    name: modelId,
    api: "openai-completions",
    provider: PROVIDER_ID,
    baseUrl: LOCAL_BASE_URL,
    reasoning: false,
    input: ["text"],
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
    contextWindow: 8192,
    maxTokens: 2048,
  };
}

/**
 * Initialise pi-web-ui's IndexedDB-backed storage. ChatPanel/AgentInterface
 * read settings and provider keys from this global storage at runtime.
 */
async function initStorage(): Promise<AppStorage> {
  const settings = new SettingsStore();
  const providerKeys = new ProviderKeysStore();
  const sessions = new SessionsStore();
  const customProviders = new CustomProvidersStore();

  const backend = new IndexedDBStorageBackend({
    dbName: "mobilegem-webui",
    version: 1,
    stores: [
      settings.getConfig(),
      providerKeys.getConfig(),
      sessions.getConfig(),
      SessionsStore.getMetadataConfig(),
      customProviders.getConfig(),
    ],
  });

  settings.setBackend(backend);
  providerKeys.setBackend(backend);
  sessions.setBackend(backend);
  customProviders.setBackend(backend);

  const storage = new AppStorage(settings, providerKeys, sessions, customProviders, backend);
  setAppStorage(storage);
  return storage;
}

async function initApp(): Promise<void> {
  const app = document.getElementById("app");
  if (!app) throw new Error("App container #app not found");

  // Register the built-in API providers so "openai-completions" can stream.
  registerBuiltInApiProviders();

  const storage = await initStorage();

  // pi-web-ui's AgentInterface refuses to send a message unless a provider key
  // exists for the active model's provider. The local server needs no auth, so
  // we seed a constant sentinel to unconditionally satisfy that gate.
  await storage.providerKeys.set(PROVIDER_ID, LOCAL_API_KEY);

  const modelId = await resolveModelId();
  const model = buildLocalModel(modelId);

  const agent = new Agent({
    initialState: {
      systemPrompt: "You are a helpful assistant running on-device.",
      model,
      thinkingLevel: "off",
      messages: [],
      tools: [],
    },
    convertToLlm: defaultConvertToLlm,
    getApiKey: async () => LOCAL_API_KEY,
  });

  const chatPanel = new ChatPanel();
  // Belt-and-suspenders: if the provider-key gate is ever hit, approve it
  // silently rather than prompting — there is only one local model and no key
  // to enter.
  await chatPanel.setAgent(agent, {
    onApiKeyRequired: async () => true,
  });

  // Model selection happens ONLY in the Android Settings screen. Suppress the
  // chat panel's own model picker so the single loaded model is forced through.
  if (chatPanel.agentInterface) {
    chatPanel.agentInterface.enableModelSelector = false;
  }

  app.replaceChildren(chatPanel);

  // INVARIANT (b): every completed/streamed message is shown in the transcript.
  //
  // pi-agent-core mutates `agent.state.messages` IN PLACE (Agent.messages getter
  // always returns the same internal array; new messages are push()ed onto it).
  // pi-web-ui's stable <message-list> is bound with `.messages=${state.messages}`
  // and relies on Lit's `!==` property dirty-check to re-render. Because the array
  // reference never changes, that binding never fires after the initial (empty)
  // render, so finalized messages never appear — the symptom is a chat that shows
  // a cursor and then nothing. AgentInterface.requestUpdate() re-runs its own
  // template but cannot wake the child, since the bound value is reference-equal.
  //
  // Force the stable list to re-render on every agent event. requestUpdate() makes
  // MessageList re-read the (in-place mutated) array, which already holds the
  // latest messages, so the transcript stays correct without touching node_modules.
  agent.subscribe(() => {
    const messageList = chatPanel.querySelector("message-list") as
      | (HTMLElement & { requestUpdate?: () => void })
      | null;
    messageList?.requestUpdate?.();
  });
}

void initApp();
