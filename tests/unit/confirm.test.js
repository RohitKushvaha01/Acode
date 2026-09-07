import { Window } from "happy-dom";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
vi.mock("lib/settings", () => ({
	default: { value: { confirmOnExit: false } },
}));
vi.mock("lib/restoreTheme", () => ({ default: vi.fn() }));
vi.mock("components/checkbox", () => ({
	default: () => document.createElement("input"),
}));
import confirm from "dialogs/confirm";
import actionStack from "lib/actionStack";
let window;
beforeEach(() => {
	vi.useFakeTimers();
	vi.clearAllMocks();
	actionStack.setMark();
	window = new Window();
	vi.stubGlobal("document", window.document);
	vi.stubGlobal("app", window.document.body);
	vi.stubGlobal("strings", { ok: "OK", cancel: "Cancel" });
	vi.stubGlobal("tag", (name, options) => {
		const el = document.createElement(name);
		for (const [key, value] of Object.entries(options)) {
			if (key === "children") el.append(...value);
			else if (value !== undefined) el[key] = value;
		}
		return el;
	});
});
afterEach(() => {
	actionStack.clearFromMark();
	vi.runAllTimers();
	vi.restoreAllMocks();
	vi.useRealTimers();
	vi.unstubAllGlobals();
	window.happyDOM.cancelAsync();
});
it.each([
	"back",
	"cancel",
	"abort",
])("settles %s as cancellation and cleans the dialog", async (method) => {
	const controller = new AbortController();
	const result = confirm("Icon", "Watch?", false, {
		signal: controller.signal,
	});
	if (method === "back") await actionStack.pop();
	if (method === "cancel") document.querySelector("button").click();
	if (method === "abort") controller.abort();
	await expect(result).resolves.toBe(false);
	vi.runAllTimers();
	expect(app.children.length).toBe(0);
	expect(actionStack.length).toBe(0);
});
it("preserves checkbox response and ignores subsequent dismissals", async () => {
	const push = vi.spyOn(actionStack, "push");
	const remove = vi.spyOn(actionStack, "remove");
	const controller = new AbortController();
	const result = confirm("Icon", "Watch?", false, {
		checkboxText: "Remember",
		returnState: true,
		signal: controller.signal,
	});
	document.querySelector("input").checked = true;
	document.querySelectorAll("button")[1].click();
	controller.abort();
	push.mock.calls[0][0].action();
	await expect(result).resolves.toEqual({ confirmed: true, checked: true });
	expect(remove).toHaveBeenCalledOnce();
	expect(actionStack.length).toBe(0);
});

it.each(["back", "cancel", "ok", "abort"])(
	"preserves the lower confirmation's Back handler after %s dismisses the top",
	async (method) => {
		const firstClosed = vi.fn();
		confirm("First", "First confirmation").then(firstClosed);
		const controller = new AbortController();
		const second = confirm("Second", "Second confirmation", false, {
			signal: controller.signal,
		});
		const top = document.querySelectorAll(".confirm")[1];
		if (method === "back") await actionStack.pop();
		if (method === "cancel") top.querySelector("button").click();
		if (method === "ok") top.querySelectorAll("button")[1].click();
		if (method === "abort") controller.abort();
		await expect(second).resolves.toBe(method === "ok");
		vi.runAllTimers();
		expect(document.querySelectorAll(".confirm")).toHaveLength(1);
		expect(actionStack.length).toBe(1);
		expect(firstClosed).not.toHaveBeenCalled();

		await actionStack.pop();
		expect(firstClosed).toHaveBeenCalledExactlyOnceWith(false);
		vi.runAllTimers();
		expect(app.children.length).toBe(0);
		expect(actionStack.length).toBe(0);
	},
);

it("preserves the top confirmation when the lower one is aborted", async () => {
	const controller = new AbortController();
	const first = confirm("First", "First confirmation", false, {
		signal: controller.signal,
	});
	const secondClosed = vi.fn();
	confirm("Second", "Second confirmation").then(secondClosed);
	controller.abort();
	await expect(first).resolves.toBe(false);
	expect(actionStack.length).toBe(1);
	expect(secondClosed).not.toHaveBeenCalled();
	await actionStack.pop();
	expect(secondClosed).toHaveBeenCalledExactlyOnceWith(false);
	vi.runAllTimers();
	expect(app.children.length).toBe(0);
	expect(actionStack.length).toBe(0);
});
