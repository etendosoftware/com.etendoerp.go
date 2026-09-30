# Agent chat history API (`/sws/agent-chat/*`)

The Etendo agent chat runs its model in the AI BFF, but its history is stored in the same tables as
the legacy Copilot panel (`ETCOP_CONVERSATION` / `ETCOP_MESSAGE`, module `com.etendoerp.copilot`), so
one history list serves both.

## Why a servlet in this module

`/sws/copilot/*` authenticates with a bearer JWT only. The Etendo Go SPA holds a `__Host-` cookie
session and no bearer, so every `/sws/copilot/*` call answers 401 (even for a path that does not
exist). Changing how `/sws/copilot/*` authenticates would touch the common Copilot path, so this
module fronts the same persistence instead:

* `AgentChatConversationsServlet`, mapped to `/sws/agent-chat/*` (AD_MODEL_OBJECT + mapping, like
  `/sws/support/*`), authenticates with `JwtAuthUtils.authenticateOrFail` (cookie session plus
  `X-Go-CSRF` for POST, or the legacy Bearer while it is enabled).
* It is a servlet and not a NEO pseudo-spec (`NeoGoWebhookBridge`, `neo-headless.md` 4.10/4.11)
  because the bridge is GET-only and answers `{"result": "<string>"}` for `Map<String,String>`
  webhooks, while this API needs JSON POST bodies, path parameters and CSRF-protected writes.
  `SupportConversationsServlet` is the sibling with that shape.
* No persistence code lives here. It calls `ConversationWriteUtils` (Copilot module), whose owner-checked
  methods (`getOwnedConversationMessages`, `renameOwnedConversation`, `setOwnedConversationActive`,
  `deleteOwnedConversation`, `createConversation`, `appendMessages`) take plain arguments.

## Ownership

Every operation acts on the session user's own conversations. Reading, renaming, archiving,
restoring or deleting a conversation that belongs to someone else is refused and reported as
`404 Conversation not found`, exactly like a missing one. (The legacy `/sws/copilot` by-id endpoints
have no ownership check; they are unchanged.)

## Routes

| Method and path | Body | Response |
|---|---|---|
| `GET /conversations` | | `{"conversations":[{"id","title"}]}` active, no app, newest first |
| `GET /conversations/archived` | | same shape, archived |
| `GET /conversations/{id}/messages` | | `{"messages":[{"id","role","content","timestamp"}]}` |
| `POST /conversations` | `{title?, external_id?, app_id?}` | `{"success":true,"conversation_id","created"}` |
| `POST /conversations/{id}/messages` | `{messages:[{role:"user"\|"assistant", text, external_id?, metadata?}]}` | `{"success","conversation_id","saved","skipped","messages":[...]}` |
| `POST /conversations/{id}/rename` | `{title}` | `{"success":true,"title"}` |
| `POST /conversations/{id}/archive` | | `{"success":true}` |
| `POST /conversations/{id}/restore` | | `{"success":true}` |
| `POST /conversations/{id}/permanent-delete` | | `{"success":true}` |

`create` and `messages` follow `docs/conversation-write-api.md` of the Copilot module (idempotent on
`external_id`, batch validated before anything is written, up to 100 messages). For `messages` the
id in the path wins over any `conversation_id` in the body.

Errors are `{"error": "<message>"}`: `401/403` from authentication, `404` for an unknown path or a
missing/foreign conversation, `400` for invalid input, `500` for anything else (generic message).
Writes are committed only on success and rolled back otherwise.

## Notes

* Requests run in admin mode as the session user (the `OBContext` user after authentication), so
  the ownership check on `AD_USER_ID` is the guard, not entity access rules.
* The Java module depends on `com.etendoerp.copilot` classes (it already does through
  `copilot.extensions` / `copilot.toolpack` in `build.gradle`); no new `AD_MODULE_DEPENDENCY` row was
  added.
