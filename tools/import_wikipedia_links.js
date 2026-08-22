#!/usr/bin/env node

const fs = require("fs");
const path = require("path");

const repositoryRoot = path.resolve(__dirname, "..");
const defaultJsonPath = path.join(repositoryRoot, "app", "src", "main", "assets", "paris_monuments.json");

function parseCsv(text) {
    const rows = [];
    let row = [];
    let field = "";
    let quoted = false;
    for (let index = 0; index < text.length; index += 1) {
        const character = text[index];
        const next = text[index + 1];
        if (quoted) {
            if (character === '"' && next === '"') {
                field += '"';
                index += 1;
            } else if (character === '"') {
                quoted = false;
            } else {
                field += character;
            }
        } else if (character === '"') {
            quoted = true;
        } else if (character === ",") {
            row.push(field);
            field = "";
        } else if (character === "\n") {
            row.push(field.replace(/\r$/, ""));
            rows.push(row);
            row = [];
            field = "";
        } else {
            field += character;
        }
    }
    if (field.length > 0 || row.length > 0) {
        row.push(field.replace(/\r$/, ""));
        rows.push(row);
    }
    return rows;
}

function wikipediaUrl(value) {
    const raw = String(value || "").trim();
    if (!raw || /^wikipedia\s*:/i.test(raw)) return null;

    const candidates = raw.split(/\s*;\s*/).map((candidate) => candidate.trim()).filter(Boolean);
    const directUrl = candidates.find((candidate) => /^https?:\/\/[^\s]+wikipedia\.org\//i.test(candidate));
    if (directUrl) return directUrl;

    const localizedTitle = candidates
        .map((candidate) => candidate.match(/^([a-z]{2,12}):(.+)$/i))
        .find((match) => match && !/^\/\//.test(match[2].trim()));
    if (localizedTitle) {
        const language = localizedTitle[1].toLowerCase();
        const title = localizedTitle[2].trim();
        if (title) return `https://${language}.wikipedia.org/wiki/${encodeURIComponent(title.replace(/ /g, "_"))}`;
    }

    if (!raw.includes(":") && raw.length < 200) {
        return `https://fr.wikipedia.org/wiki/${encodeURIComponent(raw.replace(/ /g, "_"))}`;
    }
    return null;
}

function addMapping(mapping, reference, value) {
    const normalizedReference = String(reference || "").trim();
    const url = wikipediaUrl(value);
    if (!normalizedReference || !url) return;
    for (const referencePart of normalizedReference.split(/\s*;\s*/)) {
        if (referencePart && !mapping.has(referencePart)) mapping.set(referencePart, url);
    }
}

function main() {
    const csvPath = process.argv[2];
    const jsonPath = process.argv[3] || defaultJsonPath;
    if (!csvPath) {
        console.error("Usage: node tools/import_wikipedia_links.js <catalogue.csv> [paris_monuments.json]");
        process.exitCode = 2;
        return;
    }

    const rows = parseCsv(fs.readFileSync(csvPath, "utf8"));
    const header = rows.shift().map((value) => value.trim());
    const wikipediaIndex = header.findIndex((value) => value.toLowerCase() === "wikipedia");
    const referenceIndexes = header
        .map((value, index) => ({ value: value.toLowerCase(), index }))
        .filter(({ value }) => value === "ref:mhs" || value === "ref:fr:merimee")
        .map(({ index }) => index);
    if (wikipediaIndex < 0 || referenceIndexes.length === 0) {
        throw new Error("Le CSV doit contenir une colonne wikipedia et une colonne ref:mhs ou ref:FR:Merimee.");
    }

    const links = new Map();
    for (const row of rows) {
        const wikipedia = row[wikipediaIndex];
        for (const referenceIndex of referenceIndexes) addMapping(links, row[referenceIndex], wikipedia);
    }

    const monuments = JSON.parse(fs.readFileSync(jsonPath, "utf8"));
    let matched = 0;
    for (const monument of monuments) {
        const url = links.get(String(monument.merimeeReference || "").trim());
        if (url) {
            monument.wikipediaUrl = url;
            matched += 1;
        } else {
            delete monument.wikipediaUrl;
        }
    }
    fs.writeFileSync(jsonPath, `${JSON.stringify(monuments, null, 2)}\n`, "utf8");
    console.log(`Liens Wikipédia importés : ${matched}/${monuments.length} monuments.`);
}

main();
