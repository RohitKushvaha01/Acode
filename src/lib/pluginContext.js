const exec = (resolve, reject, action, args) =>
	cordova.exec(resolve, reject, "Tee", action, args);

class PluginContext {
	constructor(uuid) {
		this.created_at = Date.now();
		this.uuid = uuid;
		Object.freeze(this);
	}

	toString() {
		return this.uuid;
	}

	[Symbol.toPrimitive](hint) {
		if (hint === "number") {
			return NaN; // prevent numeric coercion
		}
		return this.uuid;
	}

	grantedPermission(permission) {
		return new Promise((resolve, reject) => {
			exec(resolve, reject, "grantedPermission", [this.uuid, permission]);
		});
	}

	listAllPermissions() {
		return new Promise((resolve, reject) => {
			exec(resolve, reject, "listAllPermissions", [this.uuid]);
		});
	}

	getSecret(key, defaultValue = "") {
		return new Promise((resolve, reject) => {
			exec(resolve, reject, "get_secret", [
				this.uuid,
				key,
				defaultValue,
			]);
		});
	}

	setSecret(key, value) {
		return new Promise((resolve, reject) => {
			exec(resolve, reject, "set_secret", [this.uuid, key, value]);
		});
	}

	deleteSecret(key) {
		return new Promise((resolve, reject) => {
			exec(resolve, reject, "delete_secret", [this.uuid, key]);
		});
	}

	clearAllSecrets() {
		return new Promise((resolve, reject) => {
			exec(resolve, reject, "clear_all_secrets", [this.uuid]);
		});
	}

	//plugins dont need to call this
	invalidate() {
		return new Promise((resolve, reject) => {
			exec(resolve, reject, "invalidate", [this.uuid]);
		});
	}
}

// Encapsulates the trusted native session.
class TrustedSession {
	#session = null;
	#sessionPromise = null;

	// Establishes the connection (once) and resolves to a boolean. The session
	// secret is deliberately never returned to callers.
	connectInternal() {
		if (!this.#sessionPromise) {
			this.#sessionPromise = new Promise((resolve) => {
				cordova.exec(
					(session) => {
						this.#session = session;
						resolve(true);
					},
					() => resolve(false),
					"Tee",
					"establishConnection",
					[],
				);
			});
		}
		return this.#sessionPromise;
	}

	async generateInternal(pluginId, pluginJson) {
		try {
			const connected = await this.connectInternal();
			if (!connected || !this.#session) {
				console.warn(
					`PluginContext creation failed for pluginId ${pluginId}: no trusted session`,
				);
				return null;
			}

			//requesting a token with our session since we are in a privileged context
			const uuid = await new Promise((resolve, reject) => {
				cordova.exec(resolve, reject, "Tee", "requestToken", [
					this.#session,
					pluginId,
					pluginJson,
				]);
			});
			return new PluginContext(uuid);
		} catch (err) {
			console.warn(
				`PluginContext creation failed for pluginId ${pluginId}:`,
				err,
			);
			return null;
		}
	}
}

const trustedSession = new TrustedSession();

export function connect() {
	return trustedSession.connectInternal();
}

export default function generate(pluginId, pluginJson) {
	return trustedSession.generateInternal(pluginId, pluginJson);
}
