// SAT Tutoring — front-end entry point.
// Talks to the Java backend over JSON endpoints as they are added.

async function api(path, body) {
  const res = await fetch(path, {
    method: body ? "POST" : "GET",
    headers: { "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) throw new Error(path + " -> " + res.status);
  return res.json();
}

document.addEventListener("DOMContentLoaded", () => {
  const app = document.getElementById("app");
  if (app) app.textContent = "Ready.";
});
