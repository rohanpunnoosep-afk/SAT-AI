// SAT Tutoring — front-end entry point.
// Plain ES2019, no build step. Talks to the Java backend over JSON endpoints.

async function api(path, body, method) {
  const res = await fetch(path, {
    method: method || (body ? "POST" : "GET"),
    credentials: "same-origin",
    headers: body ? { "Content-Type": "application/json" } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  let payload = null;
  try {
    payload = await res.json();
  } catch (e) {
    payload = null;
  }
  if (!res.ok) {
    const err = new Error((payload && payload.error) || (path + " -> " + res.status));
    err.status = res.status;
    err.payload = payload;
    throw err;
  }
  return payload;
}

const state = {
  currentQuestion: null,
};

function showError(message) {
  const banner = document.getElementById("error-banner");
  banner.textContent = message;
  banner.hidden = false;
}

function clearError() {
  const banner = document.getElementById("error-banner");
  banner.hidden = true;
  banner.textContent = "";
}

function populateSelect(select, values) {
  while (select.options.length > 1) {
    select.remove(1);
  }
  values.forEach((v) => {
    const opt = document.createElement("option");
    opt.value = v;
    opt.textContent = v;
    select.appendChild(opt);
  });
}

async function loadFilters() {
  const meta = await api("/api/meta/filters");
  populateSelect(document.getElementById("filter-section"), meta.sections || []);
  populateSelect(document.getElementById("filter-domain"), meta.domains || []);
  populateSelect(document.getElementById("filter-skill"), meta.skills || []);
  populateSelect(document.getElementById("filter-difficulty"), meta.difficulties || []);
}

function currentFilterParams() {
  const params = new URLSearchParams();
  const section = document.getElementById("filter-section").value;
  const domain = document.getElementById("filter-domain").value;
  const skill = document.getElementById("filter-skill").value;
  const difficulty = document.getElementById("filter-difficulty").value;
  const search = document.getElementById("filter-search").value.trim();
  if (section) params.set("section", section);
  if (domain) params.set("domain", domain);
  if (skill) params.set("skill", skill);
  if (difficulty) params.set("difficulty", difficulty);
  if (search) params.set("search", search);
  return params;
}

async function runQuestionQuery() {
  const params = currentFilterParams();
  const qs = params.toString();
  const questions = await api("/api/questions" + (qs ? "?" + qs : ""));
  renderQuestionList(questions);
}

function renderQuestionList(questions) {
  const list = document.getElementById("question-list");
  list.innerHTML = "";
  document.getElementById("result-count").textContent =
    questions.length + (questions.length === 1 ? " result" : " results");

  questions.forEach((q) => {
    const li = document.createElement("li");
    li.className = "question-row";
    li.dataset.id = q.id;

    const label = document.createElement("span");
    label.className = "question-row-label";
    label.textContent = (q.skill || q.domain || q.section || "Question") +
      " · " + (q.difficulty || "?") + " · " + (q.question_type || "?");
    li.appendChild(label);

    if (q.source === "ai_generated") {
      const badge = document.createElement("span");
      badge.className = "badge badge-ai";
      badge.textContent = "AI";
      li.appendChild(badge);
    }

    li.addEventListener("click", () => loadQuestion(q.id));
    list.appendChild(li);
  });
}

async function loadQuestion(id) {
  try {
    const question = await api("/api/questions/" + encodeURIComponent(id));
    state.currentQuestion = question;
    renderQuestionDetail(question);
    clearError();
  } catch (e) {
    showError("Could not load question: " + e.message);
  }
}

function renderQuestionDetail(question) {
  const detail = document.getElementById("question-detail");
  detail.hidden = false;

  const stimulusEl = document.getElementById("question-stimulus");
  stimulusEl.innerHTML = question.stimulus || "";
  stimulusEl.hidden = !question.stimulus;

  document.getElementById("question-stem").innerHTML = question.stem;

  const answerForm = document.getElementById("question-answer-form");
  answerForm.innerHTML = "";

  if (question.question_type === "spr") {
    const input = document.createElement("input");
    input.type = "text";
    input.id = "spr-input";
    input.placeholder = "Enter your answer";
    answerForm.appendChild(input);
  } else {
    const choices = question.choices || [];
    choices.forEach((choice) => {
      const optionLabel = document.createElement("label");
      optionLabel.className = "choice-option";

      const radio = document.createElement("input");
      radio.type = "radio";
      radio.name = "mcq-choice";
      radio.value = choice.id;

      optionLabel.appendChild(radio);
      const span = document.createElement("span");
      span.innerHTML = choice.text;
      optionLabel.appendChild(span);

      answerForm.appendChild(optionLabel);
    });
  }

  const resultArea = document.getElementById("submit-result");
  resultArea.textContent = "";
  resultArea.className = "submit-result";

  document.getElementById("answer-area").innerHTML = "";

  const generateBtn = document.getElementById("generate-similar");
  generateBtn.disabled = false;
  generateBtn.textContent = "Generate similar question";
}

function getSubmittedValue(question) {
  if (question.question_type === "spr") {
    const input = document.getElementById("spr-input");
    return input ? input.value.trim() : "";
  }
  const checked = document.querySelector('input[name="mcq-choice"]:checked');
  return checked ? checked.value : "";
}

async function submitAnswer() {
  const question = state.currentQuestion;
  if (!question) return;
  const submitted = getSubmittedValue(question);
  const resultArea = document.getElementById("submit-result");
  if (!submitted) {
    resultArea.textContent = "Please enter or select an answer first.";
    return;
  }
  try {
    const result = await api("/api/session/answer", { questionId: question.id, submitted: submitted });
    resultArea.textContent = result.correct ? "Correct!" : "Not quite.";
    resultArea.className = "submit-result " + (result.correct ? "correct" : "incorrect");
    await refreshSidebar();
    clearError();
  } catch (e) {
    showError("Could not submit answer: " + e.message);
  }
}

async function revealAnswer() {
  const question = state.currentQuestion;
  if (!question) return;
  const answerArea = document.getElementById("answer-area");
  try {
    const answer = await api("/api/questions/" + encodeURIComponent(question.id) + "/answer");
    answerArea.innerHTML =
      "<div class=\"correct-answer\"><strong>Answer:</strong> " + escapeHtml(answer.correct_answer) + "</div>" +
      "<div class=\"explanation\">" + (answer.explanation || "") + "</div>";
    clearError();
  } catch (e) {
    showError("Could not load answer: " + e.message);
  }
}

function escapeHtml(str) {
  const div = document.createElement("div");
  div.textContent = str == null ? "" : String(str);
  return div.innerHTML;
}

async function generateSimilar() {
  const question = state.currentQuestion;
  if (!question) return;
  const btn = document.getElementById("generate-similar");
  btn.disabled = true;
  btn.textContent = "Generating...";
  try {
    const generated = await api("/api/questions/" + encodeURIComponent(question.id) + "/generate", {}, "POST");
    state.currentQuestion = generated;
    renderQuestionDetail(generated);
    clearError();
  } catch (e) {
    if (e.status === 503) {
      showError("AI generation unavailable: OPENAI_API_KEY is not configured on the server.");
    } else {
      showError("Could not generate a similar question: " + e.message);
    }
  } finally {
    btn.disabled = false;
    btn.textContent = "Generate similar question";
  }
}

async function refreshSidebar() {
  try {
    const [reviewData, stats] = await Promise.all([
      api("/api/session/review-list"),
      api("/api/session/stats"),
    ]);
    renderReviewList(reviewData.topics || []);
    renderSessionStats(stats);
  } catch (e) {
    showError("Could not load review list: " + e.message);
  }
}

function renderReviewList(topics) {
  const list = document.getElementById("review-list");
  list.innerHTML = "";
  if (topics.length === 0) {
    const empty = document.createElement("li");
    empty.className = "review-empty";
    empty.textContent = "No weak topics yet — answer some questions to build your review list.";
    list.appendChild(empty);
    return;
  }
  const sorted = topics.slice().sort((a, b) => a.accuracy - b.accuracy);
  sorted.forEach((topic) => {
    const li = document.createElement("li");
    li.className = "review-row";
    li.textContent = topic.domain + " — " + topic.skill + " (" + topic.correct + "/" + topic.attempts + ")";
    li.addEventListener("click", () => {
      document.getElementById("filter-domain").value = topic.domain;
      document.getElementById("filter-skill").value = topic.skill;
      runQuestionQuery().catch((e) => showError("Could not load questions: " + e.message));
    });
    list.appendChild(li);
  });
}

function renderSessionStats(stats) {
  const el = document.getElementById("session-stats");
  el.textContent = "Answered " + stats.answered + " · Correct " + stats.correct;
}

document.addEventListener("DOMContentLoaded", () => {
  document.getElementById("apply-filters").addEventListener("click", () => {
    runQuestionQuery().catch((e) => showError("Could not load questions: " + e.message));
  });
  document.getElementById("submit-answer").addEventListener("click", submitAnswer);
  document.getElementById("show-answer").addEventListener("click", revealAnswer);
  document.getElementById("generate-similar").addEventListener("click", generateSimilar);

  Promise.all([loadFilters(), runQuestionQuery(), refreshSidebar()]).catch((e) => {
    showError("Could not load initial data: " + e.message);
  });
});
