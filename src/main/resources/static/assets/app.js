const shell = document.querySelector("main[data-discoveries-url]");

if (shell) {
  const loading = document.getElementById("discoveries-loading");
  const empty = document.getElementById("discoveries-empty");
  const denied = document.getElementById("discoveries-denied");
  const list = document.getElementById("discoveries-list");

  const show = (element) => {
    [loading, empty, denied, list].forEach((node) => {
      if (node) node.hidden = node !== element;
    });
  };

  show(loading);
  const loadingStartedAt = Date.now();
  const afterMinimumLoading = (callback) => {
    const remaining = Math.max(0, 200 - (Date.now() - loadingStartedAt));
    setTimeout(callback, remaining);
  };

  fetch(shell.dataset.discoveriesUrl, { headers: { Accept: "application/json" } })
    .then(async (response) => {
      if (!response.ok) throw new Error(String(response.status));
      return response.json();
    })
    .then((discoveries) => {
      if (!Array.isArray(discoveries) || discoveries.length === 0) {
        afterMinimumLoading(() => show(empty));
        return;
      }

      list.replaceChildren(
        ...discoveries.map((discovery) => {
          const item = document.createElement("li");
          const title = document.createElement("h2");
          const objective = document.createElement("p");
          const link = document.createElement("a");
          title.textContent = discovery.title;
          objective.textContent = discovery.objective;
          link.textContent = "Open Discovery";
          link.href = `/app/discoveries/${encodeURIComponent(discovery.id)}`;
          link.className = "button";
          item.append(title, objective, link);
          return item;
        }),
      );
      afterMinimumLoading(() => show(list));
    })
    .catch(() => afterMinimumLoading(() => show(denied)));
}
