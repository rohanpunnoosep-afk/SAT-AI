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
  boxes: null,
  // The box currently taking over the question list ({id, label}), or null for
  // the ordinary filter-driven list.
  activeBox: null,
};

const MATHML_NS = "http://www.w3.org/1998/Math/MathML";
const ATTEMPTED_KEY = "sat.attempted";

// Chrome's MathML Core dropped <mfenced> (MathML 3), which the College Board bank
// still uses -- the browser renders the contents but silently drops the fences, so
// "(hx + k)(x + j)" comes out as "hx + k x + j". Rewrite it into the <mo> form that
// every renderer understands.
function expandMfenced(root) {
  let fenced = root.querySelector("mfenced");
  while (fenced) {
    const open = fenced.hasAttribute("open") ? fenced.getAttribute("open") : "(";
    const close = fenced.hasAttribute("close") ? fenced.getAttribute("close") : ")";
    const separators = (fenced.hasAttribute("separators")
      ? fenced.getAttribute("separators") : ",").replace(/\s+/g, "");

    const mrow = document.createElementNS(MATHML_NS, "mrow");
    if (open) mrow.appendChild(fenceOperator(open));
    Array.prototype.slice.call(fenced.children).forEach((child, i) => {
      if (i > 0 && separators) {
        mrow.appendChild(fenceOperator(separators[Math.min(i - 1, separators.length - 1)]));
      }
      mrow.appendChild(child);
    });
    if (close) mrow.appendChild(fenceOperator(close));

    fenced.parentNode.replaceChild(mrow, fenced);
    // Nested fences moved into mrow are still under root, so keep going.
    fenced = root.querySelector("mfenced");
  }
}

function fenceOperator(text) {
  const mo = document.createElementNS(MATHML_NS, "mo");
  mo.textContent = text;
  return mo;
}

/** Sets bank-authored HTML on an element, normalizing MathML the browser cannot render. */
function setContentHtml(el, html) {
  el.innerHTML = html || "";
  expandMfenced(el);
  return el;
}

function loadAttempted() {
  try {
    const raw = window.localStorage.getItem(ATTEMPTED_KEY);
    return new Set(raw ? JSON.parse(raw) : []);
  } catch (e) {
    return new Set();
  }
}

function saveAttempted(ids) {
  try {
    window.localStorage.setItem(ATTEMPTED_KEY, JSON.stringify(Array.from(ids)));
  } catch (e) {
    // Storage unavailable (private mode); checkboxes still work for this page view.
  }
}

function setAttempted(questionId, attempted) {
  const ids = loadAttempted();
  if (attempted) {
    ids.add(questionId);
  } else {
    ids.delete(questionId);
  }
  saveAttempted(ids);
}

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

/** One clickable row in the question list. Shared by the filter list and box lists. */
function buildQuestionRow(q, attempted) {
  const li = document.createElement("li");
  li.className = "question-row";
  li.dataset.id = q.id;

  const check = document.createElement("input");
  check.type = "checkbox";
  check.className = "question-check";
  check.checked = attempted.has(q.id);
  check.title = "Attempted";
  check.setAttribute("aria-label", "Mark question as attempted");
  // Ticking the box must not also open the question.
  check.addEventListener("click", (e) => e.stopPropagation());
  check.addEventListener("change", () => setAttempted(q.id, check.checked));
  li.appendChild(check);

  const main = document.createElement("span");
  main.className = "question-row-main";

  const label = document.createElement("span");
  label.className = "question-row-label";
  label.textContent = (q.skill || q.domain || q.section || "Question") +
    " · " + (q.difficulty || "?") + " · " + (q.question_type || "?");
  main.appendChild(label);

  if (q.cb_question_id) {
    const cbId = document.createElement("span");
    cbId.className = "cb-id";
    cbId.title = "College Board question ID";
    cbId.textContent = q.cb_question_id;
    main.appendChild(cbId);
  }

  li.appendChild(main);

  if (q.source === "ai_generated") {
    const badge = document.createElement("span");
    badge.className = "badge badge-ai";
    badge.textContent = "AI";
    li.appendChild(badge);
  }

  li.addEventListener("click", () => loadQuestion(q.id));
  return li;
}

function setResultCount(text) {
  document.getElementById("result-count").textContent = text;
}

function renderQuestionList(questions) {
  const list = document.getElementById("question-list");
  list.innerHTML = "";
  setResultCount(questions.length + (questions.length === 1 ? " result" : " results"));

  const attempted = loadAttempted();
  questions.forEach((q) => list.appendChild(buildQuestionRow(q, attempted)));

  markActiveRow(state.currentQuestion ? state.currentQuestion.id : null);
}

/** Highlights the open question once the list has moved into the sidebar. */
function markActiveRow(questionId) {
  document.querySelectorAll(".question-row").forEach((row) => {
    row.classList.toggle("active", !!questionId && row.dataset.id === questionId);
  });
}

function setRowChecked(questionId, checked) {
  document.querySelectorAll(".question-row").forEach((row) => {
    if (row.dataset.id === questionId) {
      const box = row.querySelector(".question-check");
      if (box) box.checked = checked;
    }
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
  // Once a question is open the list collapses into the left sidebar and the
  // question itself takes the top of the main column.
  document.getElementById("layout").classList.add("detail-open");
  markActiveRow(question.id);

  const cbIdEl = document.getElementById("question-cb-id");
  cbIdEl.textContent = question.cb_question_id
    ? "College Board question ID: " + question.cb_question_id : "";
  cbIdEl.hidden = !question.cb_question_id;

  const stimulusEl = document.getElementById("question-stimulus");
  setContentHtml(stimulusEl, question.stimulus);
  stimulusEl.hidden = !question.stimulus;

  setContentHtml(document.getElementById("question-stem"), question.stem);

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
    choices.forEach((choice, index) => {
      answerForm.appendChild(buildChoice(choice, index));
    });
  }

  const resultArea = document.getElementById("submit-result");
  resultArea.textContent = "";
  resultArea.className = "submit-result";

  document.getElementById("answer-area").innerHTML = "";

  resetGenControls();
}

const CHOICE_LETTERS = ["A", "B", "C", "D", "E", "F"];

/**
 * One answer choice, with the lettered cross-out control the Bluebook app uses:
 * eliminating a choice strikes it through and takes it out of play, and the
 * button turns into Undo so it can be brought back.
 */
function buildChoice(choice, index) {
  const letter = CHOICE_LETTERS[index] || String(index + 1);

  const option = document.createElement("div");
  option.className = "choice-option";

  const body = document.createElement("label");
  body.className = "choice-body";

  const radio = document.createElement("input");
  radio.type = "radio";
  radio.name = "mcq-choice";
  radio.value = choice.id;
  body.appendChild(radio);

  const letterEl = document.createElement("span");
  letterEl.className = "choice-letter";
  letterEl.textContent = letter;
  body.appendChild(letterEl);

  const text = document.createElement("span");
  text.className = "choice-text";
  setContentHtml(text, choice.text);
  body.appendChild(text);

  option.appendChild(body);

  const strike = document.createElement("button");
  strike.type = "button";
  strike.className = "choice-strike";
  strike.textContent = letter;
  strike.title = "Cross out choice " + letter;
  strike.setAttribute("aria-label", "Cross out choice " + letter);
  strike.setAttribute("aria-pressed", "false");
  strike.addEventListener("click", () => {
    setChoiceEliminated(option, !option.classList.contains("eliminated"), letter);
  });
  option.appendChild(strike);

  return option;
}

function setChoiceEliminated(option, eliminated, letter) {
  const radio = option.querySelector('input[type="radio"]');
  const strike = option.querySelector(".choice-strike");

  option.classList.toggle("eliminated", eliminated);
  // A crossed-out choice is out of play until it is restored.
  radio.disabled = eliminated;
  if (eliminated) {
    radio.checked = false;
  }

  strike.textContent = eliminated ? "Undo" : letter;
  strike.title = eliminated
    ? "Undo cross out of choice " + letter : "Cross out choice " + letter;
  strike.setAttribute("aria-label", strike.title);
  strike.setAttribute("aria-pressed", eliminated ? "true" : "false");
}

/** Dismisses the open question and hands the main column back to the list. */
function closeQuestion() {
  state.currentQuestion = null;
  document.getElementById("question-detail").hidden = true;
  document.getElementById("layout").classList.remove("detail-open");
  markActiveRow(null);
}

function getSubmittedValue(question) {
  if (question.question_type === "spr") {
    const input = document.getElementById("spr-input");
    return input ? input.value.trim() : "";
  }
  const checked = document.querySelector('input[name="mcq-choice"]:checked');
  return checked ? checked.value : "";
}

/**
 * Grades whatever the user has selected. There is no separate Submit step: revealing
 * the answer *is* the submission, so a matching selection counts as correct and any
 * other selection counts as wrong (the server also files misses into a box).
 */
async function gradeCurrentAnswer(question) {
  const submitted = getSubmittedValue(question);
  const resultArea = document.getElementById("submit-result");
  if (!submitted) {
    resultArea.textContent = "Answer revealed without a selection — not counted.";
    resultArea.className = "submit-result";
    return;
  }
  const result = await api("/api/session/answer", { questionId: question.id, submitted: submitted });
  resultArea.textContent = result.correct
    ? "Correct!"
    : "Not quite — saved to \"" + (result.missed_box_label || "Missed Questions") + "\".";
  resultArea.className = "submit-result " + (result.correct ? "correct" : "incorrect");
  setAttempted(question.id, true);
  setRowChecked(question.id, true);
  // Box counts changed (a miss is filed into "Missed Questions"), so drop the cache
  // and re-pull the open box in case this question just joined it.
  state.boxes = null;
  await refreshSidebar();
  if (state.activeBox) {
    await refreshBoxQuestions();
  }
}

async function revealAnswer() {
  const question = state.currentQuestion;
  if (!question) return;
  const answerArea = document.getElementById("answer-area");
  clearError();
  // Grading must never block the reveal itself.
  try {
    await gradeCurrentAnswer(question);
  } catch (e) {
    showError("Could not record your answer: " + e.message);
  }
  try {
    const answer = await api("/api/questions/" + encodeURIComponent(question.id) + "/answer");
    setContentHtml(answerArea,
      "<div class=\"correct-answer\"><strong>Answer:</strong> " + escapeHtml(answer.correct_answer) + "</div>" +
      "<div class=\"explanation\">" + (answer.explanation || "") + "</div>");
  } catch (e) {
    showError("Could not load answer: " + e.message);
  }
}

function escapeHtml(str) {
  const div = document.createElement("div");
  div.textContent = str == null ? "" : String(str);
  return div.innerHTML;
}

function selectedGenLevel() {
  const checked = document.querySelector('input[name="gen-level"]:checked');
  return checked ? checked.value : "1";
}

/** Hides the level radios again and puts the generate button back to its resting label. */
function resetGenControls() {
  const group = document.getElementById("gen-level-group");
  group.hidden = true;
  const first = group.querySelector('input[name="gen-level"][value="1"]');
  if (first) first.checked = true;
  const btn = document.getElementById("generate-similar");
  btn.disabled = false;
  btn.textContent = "Generate similar question";
}

/**
 * First click only reveals the Level 1 / Level 2 choice — the levels stay out of the
 * control row until the user has actually asked to generate. The second click runs it.
 */
function onGenerateClick() {
  const group = document.getElementById("gen-level-group");
  if (group.hidden) {
    if (!state.currentQuestion) return;
    group.hidden = false;
    document.getElementById("generate-similar").textContent = "Generate at this level";
    return;
  }
  generateSimilar().catch((e) => showError("Could not generate a similar question: " + e.message));
}

async function generateSimilar() {
  const question = state.currentQuestion;
  if (!question) return;
  const btn = document.getElementById("generate-similar");
  btn.disabled = true;
  btn.textContent = "Generating...";
  try {
    const level = selectedGenLevel();
    const generated = await api(
      "/api/questions/" + encodeURIComponent(question.id) + "/generate?level=" + encodeURIComponent(level),
      {}, "POST");
    state.currentQuestion = generated;
    renderQuestionDetail(generated);
    clearError();
  } catch (e) {
    if (e.status === 503) {
      showError("AI generation unavailable: OPENAI_API_KEY is not configured on the server.");
    } else {
      showError("Could not generate a similar question: " + e.message);
    }
    // Leave the level choice on screen so the user can retry without re-opening it.
    btn.textContent = "Generate at this level";
  } finally {
    btn.disabled = false;
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
      exitBoxMode();
    });
    list.appendChild(li);
  });
}

function renderSessionStats(stats) {
  const el = document.getElementById("session-stats");
  el.textContent = "Answered " + stats.answered + " · Correct " + stats.correct;
}

// ---- Boxes (saved question collections) ----

async function loadBoxes() {
  state.boxes = await api("/api/boxes");
  return state.boxes;
}

async function ensureBoxesLoaded() {
  if (!state.boxes) {
    await loadBoxes();
  }
  return state.boxes;
}

async function toggleAddToBoxMenu() {
  const menu = document.getElementById("add-to-box-menu");
  if (!menu.hidden) {
    menu.hidden = true;
    return;
  }
  menu.hidden = false;
  menu.innerHTML = "Loading...";
  try {
    const boxes = await loadBoxes();
    renderAddToBoxMenu(boxes);
  } catch (e) {
    menu.innerHTML = "";
    const err = document.createElement("div");
    err.textContent = "Could not load boxes: " + e.message;
    menu.appendChild(err);
  }
}

function renderAddToBoxMenu(boxes) {
  const menu = document.getElementById("add-to-box-menu");
  menu.innerHTML = "";

  if (boxes.length === 0) {
    const createDefault = document.createElement("button");
    createDefault.type = "button";
    createDefault.textContent = "Create \"Box1\" and add";
    createDefault.addEventListener("click", () => createBoxAndAddCurrent("Box1"));
    menu.appendChild(createDefault);
  } else {
    boxes.forEach((box) => {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.textContent = box.label + " (" + box.question_count + ")";
      btn.addEventListener("click", () => addCurrentQuestionToBox(box.id, box.label));
      menu.appendChild(btn);
    });
  }

  const newRow = document.createElement("div");
  newRow.className = "add-to-box-new";
  const input = document.createElement("input");
  input.type = "text";
  input.placeholder = "New box name";
  const createBtn = document.createElement("button");
  createBtn.type = "button";
  createBtn.textContent = "Create";
  createBtn.addEventListener("click", () => {
    const label = input.value.trim();
    if (!label) return;
    createBoxAndAddCurrent(label);
  });
  newRow.appendChild(input);
  newRow.appendChild(createBtn);
  menu.appendChild(newRow);
}

async function createBoxAndAddCurrent(label) {
  try {
    const box = await api("/api/boxes", { label: label }, "POST");
    state.boxes = null;
    await addCurrentQuestionToBox(box.id, box.label);
  } catch (e) {
    showError("Could not create box: " + e.message);
  }
}

async function addCurrentQuestionToBox(boxId, label) {
  const question = state.currentQuestion;
  if (!question) return;
  try {
    await api("/api/boxes/" + encodeURIComponent(boxId) + "/questions", { questionId: question.id }, "POST");
    const menu = document.getElementById("add-to-box-menu");
    menu.innerHTML = "";
    const status = document.createElement("div");
    status.className = "add-to-box-status";
    status.textContent = "Added to " + label + ".";
    menu.appendChild(status);
    state.boxes = null;
    if (state.activeBox && state.activeBox.id === boxId) {
      await refreshBoxQuestions();
    }
    clearError();
  } catch (e) {
    showError("Could not add question to box: " + e.message);
  }
}

async function openBoxesOverlay() {
  document.getElementById("boxes-overlay").hidden = false;
  try {
    const boxes = await loadBoxes();
    renderBoxesList(boxes);
  } catch (e) {
    showError("Could not load boxes: " + e.message);
  }
}

function closeBoxesOverlay() {
  document.getElementById("boxes-overlay").hidden = true;
}

function renderBoxesList(boxes) {
  const list = document.getElementById("boxes-list");
  list.innerHTML = "";
  if (boxes.length === 0) {
    const empty = document.createElement("li");
    empty.className = "review-empty";
    empty.textContent = "No boxes yet. Create one below.";
    list.appendChild(empty);
    return;
  }
  const activeId = state.activeBox ? state.activeBox.id : null;
  boxes.forEach((box) => {
    const li = document.createElement("li");
    li.className = "review-row" + (box.id === activeId ? " active" : "");
    li.textContent = box.label + " (" + box.question_count + ")";
    li.addEventListener("click", () => {
      enterBox(box).catch((e) => showError("Could not open box: " + e.message));
    });
    list.appendChild(li);
  });
}

async function createBoxFromOverlay() {
  const input = document.getElementById("new-box-label");
  const label = input.value.trim();
  if (!label) return;
  try {
    await api("/api/boxes", { label: label }, "POST");
    input.value = "";
    const boxes = await loadBoxes();
    renderBoxesList(boxes);
    clearError();
  } catch (e) {
    showError("Could not create box: " + e.message);
  }
}

/**
 * Opening a box hands the question list over to that box: the overlay closes and
 * the box's questions render in the same panel the filter results normally use,
 * so they collapse into the sidebar and open in the detail pane just like any
 * other question.
 */
async function enterBox(box) {
  state.activeBox = { id: box.id, label: box.label };
  closeBoxesOverlay();
  document.getElementById("questions-title").textContent = box.label;
  document.getElementById("box-mode-bar").hidden = false;
  document.getElementById("box-generate-status").textContent = "";
  await refreshBoxQuestions();
}

/** Returns the list to the ordinary filter-driven results. */
function exitBoxMode() {
  state.activeBox = null;
  document.getElementById("questions-title").textContent = "Questions";
  document.getElementById("box-mode-bar").hidden = true;
  document.getElementById("box-generate-status").textContent = "";
  runQuestionQuery().catch((e) => showError("Could not load questions: " + e.message));
}

async function refreshBoxQuestions() {
  const box = state.activeBox;
  if (!box) return;
  const questions = await api("/api/boxes/" + encodeURIComponent(box.id) + "/questions");
  renderBoxQuestions(questions);
}

/**
 * The box list is split in two: the questions the user filed into the box, and the
 * AI variants generated from them. Both are ordinary clickable rows.
 */
function renderBoxQuestions(questions) {
  const list = document.getElementById("question-list");
  list.innerHTML = "";

  const saved = questions.filter((q) => q.source !== "ai_generated");
  const generated = questions.filter((q) => q.source === "ai_generated");
  setResultCount(saved.length + " saved · " + generated.length + " generated");

  const attempted = loadAttempted();

  if (questions.length === 0) {
    const empty = document.createElement("li");
    empty.className = "review-empty";
    empty.textContent = "No questions in this box yet. Open a question and use \"Add to box\".";
    list.appendChild(empty);
    return;
  }

  appendQuestionGroup(list, "Saved questions", saved, attempted,
    "No saved questions — open a question and use \"Add to box\".");
  appendQuestionGroup(list, "Generated questions", generated, attempted,
    "None yet — generate some below.");

  markActiveRow(state.currentQuestion ? state.currentQuestion.id : null);
}

function appendQuestionGroup(list, title, questions, attempted, emptyText) {
  const header = document.createElement("li");
  header.className = "question-group-header";
  header.textContent = title + " (" + questions.length + ")";
  list.appendChild(header);

  if (questions.length === 0) {
    const empty = document.createElement("li");
    empty.className = "question-group-empty";
    empty.textContent = emptyText;
    list.appendChild(empty);
    return;
  }
  questions.forEach((q) => list.appendChild(buildQuestionRow(q, attempted)));
}

async function generateForActiveBox() {
  const box = state.activeBox;
  if (!box) return;
  const count = parseInt(document.getElementById("box-gen-count").value, 10) || 1;
  const level = document.getElementById("box-gen-level").value;
  const btn = document.getElementById("box-generate-btn");
  const status = document.getElementById("box-generate-status");
  btn.disabled = true;
  btn.textContent = "Generating...";
  status.textContent = "";
  try {
    const result = await api(
      "/api/boxes/" + encodeURIComponent(box.id) + "/generate",
      { count: count, level: parseInt(level, 10) }, "POST");
    status.textContent = "Generated " + result.generated + " of " + result.requested +
      " requested question(s) — see \"Generated questions\" above.";
    // The new questions are now in the box, and the box count changed.
    state.boxes = null;
    await refreshBoxQuestions();
    clearError();
  } catch (e) {
    status.textContent = "";
    showError("Could not generate questions: " + e.message);
  } finally {
    btn.disabled = false;
    btn.textContent = "Generate similar questions";
  }
}

document.addEventListener("DOMContentLoaded", () => {
  // Applying filters always means "show me the whole bank again", so it leaves a box.
  document.getElementById("apply-filters").addEventListener("click", exitBoxMode);
  document.getElementById("exit-box").addEventListener("click", exitBoxMode);
  document.getElementById("close-question").addEventListener("click", closeQuestion);
  document.getElementById("show-answer").addEventListener("click", revealAnswer);
  document.getElementById("generate-similar").addEventListener("click", onGenerateClick);

  document.getElementById("add-to-box").addEventListener("click", (e) => {
    e.stopPropagation();
    toggleAddToBoxMenu().catch((err) => showError("Could not open box menu: " + err.message));
  });
  document.addEventListener("click", (e) => {
    const menu = document.getElementById("add-to-box-menu");
    if (!menu.hidden && !menu.contains(e.target) && e.target.id !== "add-to-box") {
      menu.hidden = true;
    }
  });

  document.getElementById("open-boxes").addEventListener("click", () => {
    openBoxesOverlay().catch((e) => showError("Could not open boxes: " + e.message));
  });
  document.getElementById("close-boxes").addEventListener("click", closeBoxesOverlay);
  document.getElementById("boxes-overlay").addEventListener("click", (e) => {
    if (e.target.id === "boxes-overlay") closeBoxesOverlay();
  });
  document.getElementById("create-box").addEventListener("click", () => {
    createBoxFromOverlay().catch((e) => showError("Could not create box: " + e.message));
  });
  document.getElementById("box-generate-btn").addEventListener("click", () => {
    generateForActiveBox().catch((e) => showError("Could not generate box questions: " + e.message));
  });

  Promise.all([loadFilters(), runQuestionQuery(), refreshSidebar()]).catch((e) => {
    showError("Could not load initial data: " + e.message);
  });
});
