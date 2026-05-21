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
const LOCAL_BASE_URL = "http://127.0.0.1:8765/v1";

// Identifier for our single custom OpenAI-compatible provider.
const PROVIDER_ID = "mobilegem-local";

// Used when the local server cannot be reached during startup.
const FALLBACK_MODEL_ID = "gemma";

/**
 * Resolve the model id from the local server's OpenAI-style /models endpoint.
 * INVARIANT (b): the model id always comes from the local /v1/models response.
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

  // The local server needs no real credentials, but AgentInterface refuses to
  // send a message unless a provider key exists. Seed a placeholder so the
  // chat works without an API-key prompt.
  const existingKey = await storage.providerKeys.get(PROVIDER_ID);
  if (!existingKey) {
    await storage.providerKeys.set(PROVIDER_ID, "local");
  }

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
    // Supplies the placeholder key for the local provider on every LLM call.
    getApiKey: async () => "local",
  });

  const chatPanel = new ChatPanel();
  await chatPanel.setAgent(agent);

  app.replaceChildren(chatPanel);
}

void initApp();
