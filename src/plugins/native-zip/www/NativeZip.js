var exec = require("cordova/exec");

/**
 * @param {string} action
 * @param {string[]} args
 * @returns {Promise<void>}
 */
function call(action, args) {
  for (const arg of args) {
    if (typeof arg !== "string" || !arg.trim()) {
      return Promise.reject(
        new Error(`Invalid argument for ${action}: ${JSON.stringify(arg)}`),
      );
    }
  }

  return new Promise((resolve, reject) => {
    exec(
      (result) => resolve(result ?? null),
      (err) => reject(new Error(err ?? `${action} failed`)),
      "NativeZip",
      action,
      args,
    );
  });
}

const NativeZip = {
  /**
   * @param {string} zipFile - Absolute path to the zip file
   * @param {string} targetDir - Absolute path to the destination directory
   * @returns {Promise<void>}
   */
  extractToDir(zipFile, targetDir) {
    return call("extractZipFileToDir", [zipFile, targetDir]);
  },

  /**
   * @param {string} sourceDir - Absolute path to the directory to compress
   * @param {string} zipFile - Absolute path for the output zip file
   * @returns {Promise<void>}
   */
  compressDir(sourceDir, zipFile) {
    return call("compressDirToZipFile", [sourceDir, zipFile]);
  },
};

module.exports = NativeZip;
