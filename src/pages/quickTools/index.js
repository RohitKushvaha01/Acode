export default async function QuickToolsSettings(options) {
	const { default: Settings } = await import("./quickTools.js");
	Settings(options);
}
