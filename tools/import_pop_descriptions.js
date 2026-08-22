#!/usr/bin/env node

const fs = require("fs");
const https = require("https");
const path = require("path");

const repositoryRoot = path.resolve(__dirname, "..");
const defaultJsonPath = path.join(repositoryRoot, "app", "src", "main", "assets", "paris_monuments.json");
const API_BASE_URL = "https://api.pop.culture.gouv.fr/notices/merimee/";
const WORKER_COUNT = 6;

function requestJson(reference, attempt = 1) {
    const url = `${API_BASE_URL}${encodeURIComponent(reference)}/public`;
    return new Promise((resolve) => {
        const request = https.get(url, {
            headers: {
                Accept: "application/json",
                "User-Agent": "LeFlaneurDeParis-catalog-import/1.0",
            },
        }, (response) => {
            let body = "";
            response.setEncoding("utf8");
            response.on("data", (chunk) => { body += chunk; });
            response.on("end", () => {
                if (response.statusCode === 404) {
                    resolve({ ok: true, description: "" });
                    return;
                }
                if (response.statusCode < 200 || response.statusCode >= 300) {
                    retryOrFail(reference, attempt, resolve, `HTTP ${response.statusCode}`);
                    return;
                }
                try {
                    const root = JSON.parse(body);
                    const notice = root.notice || {};
                    resolve({ ok: true, description: cleanDescription(notice.HIST) });
                } catch (error) {
                    retryOrFail(reference, attempt, resolve, error.message);
                }
            });
        });
        request.setTimeout(30_000, () => request.destroy(new Error("timeout")));
        request.on("error", (error) => retryOrFail(reference, attempt, resolve, error.message));
    });
}

function retryOrFail(reference, attempt, resolve, error) {
    if (attempt < 3) {
        setTimeout(() => requestJson(reference, attempt + 1).then(resolve), attempt * 500);
    } else {
        resolve({ ok: false, error: `${reference}: ${error}` });
    }
}

function cleanDescription(value) {
    const raw = Array.isArray(value) ? value.join("\n") : String(value || "");
    return raw
        .replace(/\r\n?/g, "\n")
        .replace(/[ \t]+\n/g, "\n")
        .replace(/\n{3,}/g, "\n\n")
        .trim();
}

async function main() {
    const jsonPath = process.argv[2] || defaultJsonPath;
    const monuments = JSON.parse(fs.readFileSync(jsonPath, "utf8"));
    const jobs = monuments
        .map((monument, index) => ({ monument, index, reference: String(monument.merimeeReference || "").trim() }))
        .filter((job) => job.reference);
    let completed = 0;
    let matched = 0;
    const failures = [];
    let nextJob = 0;

    async function worker() {
        while (true) {
            const job = jobs[nextJob++];
            if (!job) return;
            const result = await requestJson(job.reference);
            if (result.ok) {
                if (result.description) {
                    job.monument.popHistoricalDescription = result.description;
                    matched += 1;
                } else {
                    delete job.monument.popHistoricalDescription;
                }
            } else {
                failures.push(result.error);
            }
            completed += 1;
            if (completed % 100 === 0 || completed === jobs.length) {
                console.log(`Descriptions POP : ${completed}/${jobs.length}`);
            }
        }
    }

    await Promise.all(Array.from({ length: WORKER_COUNT }, worker));
    fs.writeFileSync(jsonPath, `${JSON.stringify(monuments, null, 2)}\n`, "utf8");
    console.log(`Descriptions historiques importées : ${matched}/${monuments.length} monuments.`);
    if (failures.length > 0) {
        console.warn(`${failures.length} requêtes POP ont échoué :`);
        failures.forEach((failure) => console.warn(`- ${failure}`));
        process.exitCode = 1;
    }
}

main().catch((error) => {
    console.error(error);
    process.exitCode = 1;
});
