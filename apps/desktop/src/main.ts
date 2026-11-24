import { createApp } from "vue";
import App from "./App.vue";
import SelectionView from "./SelectionView.vue";
import StickyNote from "./StickyNote.vue";
import "./styles.css";

const view = new URLSearchParams(window.location.search).get("view");
const root = view === "selection" ? SelectionView : view === "sticky" ? StickyNote : App;
createApp(root).mount("#app");
