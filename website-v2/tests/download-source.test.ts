import { describe, expect, test } from "bun:test";
import { assetDownloadUrl } from "../src/lib/download-source";

const asset = {
  name: "FluxDown.zip",
  size: 123,
  download_url: "/v2/api/download/FluxDown.zip?tag=extension-v1.2.3",
  github_download_url: "https://github.com/zerx-lab/FluxDown/releases/download/extension-v1.2.3/FluxDown.zip",
};

describe("website download sources", () => {
  test("default website download goes directly to GitHub without hitting the signing endpoint", () => {
    expect(assetDownloadUrl(asset, "github")).toBe(asset.github_download_url);
  });
  test("backup preserves base, component tag and filename", () => {
    expect(assetDownloadUrl(asset, "oss")).toBe(`${asset.download_url}&source=oss`);
  });
  test("old cached API responses explicitly select GitHub, not legacy OSS", () => {
    expect(assetDownloadUrl({ ...asset, github_download_url: undefined }, "github"))
      .toBe(`${asset.download_url}&source=github`);
  });
  test("source replacement preserves preview tags and does not duplicate parameters", () => {
    const result = assetDownloadUrl({ ...asset, download_url: "/api/download/a.zip?tag=v1.2.3-rc.1&source=github" }, "oss");
    expect(result).toBe("/api/download/a.zip?tag=v1.2.3-rc.1&source=oss");
  });
});
