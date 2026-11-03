"use strict";
const statusNode = document.getElementById("status");
const messages = document.getElementById("messages");
const latest = document.getElementById("latest");
const notice = document.getElementById("notice");
let previous = "", first = true;
const nearEnd = () => window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 120;
const scrollEnd = () => { window.scrollTo(0, document.documentElement.scrollHeight); latest.hidden = true; };
latest.addEventListener("click", scrollEnd);
window.addEventListener("scroll", () => { if (nearEnd()) latest.hidden = true; }, {passive: true});
async function poll() {
  try {
    const response = await fetch("./state", {cache: "no-store", signal: AbortSignal.timeout(4000)});
    if (!response.ok) throw new Error("Suivi indisponible");
    const state = await response.json();
    statusNode.textContent = !state.online ? "Assistant arrêté ou déconnecté · derniers échanges conservés à l’écran"
      : state.busy ? state.status || state.provider + " prépare la réponse…"
      : state.connection_error ? "Discussion indisponible · intervention nécessaire"
      : state.status || "À jour · pose tes questions dans le mod";
    statusNode.className = !state.online || state.connection_error ? "offline" : state.busy ? "busy" : "";
    const warning = state.connection_error || state.notice;
    notice.textContent = warning;
    notice.hidden = !warning || (!state.connection_error && state.messages.some(m => m.text === warning));
    const current = JSON.stringify([state.provider, state.messages]);
    if (current !== previous) {
      const follow = first || nearEnd();
      const fragment = document.createDocumentFragment();
      for (const message of state.messages) {
        const card = document.createElement("article");
        card.className = message.role;
        const label = document.createElement("strong");
        label.textContent = message.role === "user" ? "Toi · Minecraft" : state.provider;
        const body = document.createElement("div");
        body.className = "text";
        body.textContent = message.text; // Game/AI text is always inert; never HTML.
        card.append(label, body); fragment.append(card);
      }
      messages.replaceChildren(fragment);
      document.getElementById("empty").hidden = state.messages.length > 0;
      previous = current; first = false;
      if (follow) scrollEnd(); else latest.hidden = false;
    }
  } catch (_) {
    statusNode.textContent = "Suivi déconnecté · relance l’assistant. Les messages restent affichés.";
    statusNode.className = "offline";
  } finally {
    setTimeout(poll, document.hidden ? 5000 : 1000);
  }
}
poll();
