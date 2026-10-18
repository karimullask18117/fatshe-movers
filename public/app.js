(function () {
  "use strict";

  var form = document.getElementById("qform");
  if (!form) return;

  function $(id) { return document.getElementById(id); }
  var FIELDS = ["route", "goods", "name", "mobile", "date", "email", "from", "to"];
  var routeSel = $("route"), goodsSel = $("goods"), est = $("est"), toInput = $("to");
  var site = { routes: [], rates: {} };
  var toTouched = false;

  // Earliest shifting date is today (the server checks this again).
  try { $("date").min = new Date().toLocaleDateString("en-CA"); } catch (e) { /* older browsers */ }

  fetch("/api/site")
    .then(function (r) { if (!r.ok) throw new Error("status " + r.status); return r.json(); })
    .then(function (d) { site = d; updateEstimate(); })
    .catch(function () { est.textContent = "Rates unavailable. Please call us."; });

  function cityFor(routeId) {
    for (var i = 0; i < site.routes.length; i++) if (site.routes[i].id === routeId) return site.routes[i].city;
    return "";
  }

  function updateEstimate() {
    var r = routeSel.value, g = goodsSel.value;
    if (!r || !g) { est.textContent = "Choose a route and goods"; return; }
    var byGoods = site.rates[r] || {};
    est.textContent = byGoods[g] || "Quote on survey";
  }

  routeSel.addEventListener("change", function () {
    if (!toTouched) toInput.value = cityFor(routeSel.value);
    updateEstimate();
  });
  goodsSel.addEventListener("change", updateEstimate);
  toInput.addEventListener("input", function () { toTouched = true; });

  function clearErrors() {
    FIELDS.forEach(function (f) {
      $(f + "Err").textContent = "";
      $(f).removeAttribute("aria-invalid");
    });
    $("formError").hidden = true;
  }

  function showErrors(errors) {
    var first = null;
    FIELDS.forEach(function (f) {
      if (errors[f]) {
        $(f + "Err").textContent = errors[f];
        $(f).setAttribute("aria-invalid", "true");
        if (!first) first = $(f);
      }
    });
    if (first) first.focus();
  }

  function showFormError(message) {
    var el = $("formError");
    el.textContent = message;
    el.hidden = false;
  }

  function setBusy(busy) {
    var btn = $("submitBtn");
    btn.disabled = busy;
    btn.textContent = busy ? "Sending..." : "Send my request";
  }

  form.addEventListener("submit", function (e) {
    e.preventDefault();
    clearErrors();
    setBusy(true);

    fetch("/api/quotes", { method: "POST", body: new URLSearchParams(new FormData(form)) })
      .then(function (res) {
        return res.json().catch(function () { return {}; }).then(function (data) { return { status: res.status, data: data }; });
      })
      .then(function (r) {
        if (r.status === 200 && r.data.ok) return showSuccess(r.data);
        if (r.status === 422 && r.data.errors) return showErrors(r.data.errors);
        showFormError(r.data.error || "Something went wrong. Please call " + form.dataset.phone + ".");
      })
      .catch(function () {
        showFormError("We could not reach the server. Please check your connection or call " + form.dataset.phone + ".");
      })
      .then(function () { setBusy(false); });
  });

  function showSuccess(data) {
    $("refNo").textContent = data.reference;
    var rate = $("resultRate");
    if (data.rate) { rate.textContent = "Indicative rate for your move: " + data.rate + ". The written quote may differ after the survey."; rate.hidden = false; }
    else { rate.hidden = true; }
    var wa = $("waSend");
    if (data.whatsappUrl) { wa.href = data.whatsappUrl; wa.hidden = false; } else { wa.hidden = true; }
    form.hidden = true;
    $("result").hidden = false;
    $("resultTitle").focus();
  }

  $("againBtn").addEventListener("click", function () {
    form.reset();
    toTouched = false;
    updateEstimate();
    $("result").hidden = true;
    form.hidden = false;
    routeSel.focus();
  });
})();
