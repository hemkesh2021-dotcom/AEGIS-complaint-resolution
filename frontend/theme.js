(() => {
  const read = () => {
    try {
      return localStorage.getItem("aegisTheme");
    } catch {
      return null;
    }
  };
  const preferred = () =>
    matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
  document.documentElement.dataset.theme = read() || preferred();
  document.addEventListener("click", (event) => {
    if (!event.target.closest("[data-theme-toggle]")) return;
    const theme =
      document.documentElement.dataset.theme === "dark" ? "light" : "dark";
    document.documentElement.dataset.theme = theme;
    try {
      localStorage.setItem("aegisTheme", theme);
    } catch {}
    document.dispatchEvent(new CustomEvent("aegis:theme", { detail: theme }));
  });
})();
