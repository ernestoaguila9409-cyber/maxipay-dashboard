const { Storage } = require("@google-cloud/storage");

const BUCKET_NAME = "restaurantapp-180da.appspot.com";

const CORS_CONFIG = [
  {
    origin: [
      "https://www.maxipaypos.com",
      "https://maxipaypos.com",
      "http://localhost:3000",
    ],
    method: ["GET", "POST", "PUT"],
    responseHeader: ["Content-Type"],
    maxAgeSeconds: 3600,
  },
];

async function setCors() {
  const storage = new Storage({ projectId: "restaurantapp-180da" });
  const bucket = storage.bucket(BUCKET_NAME);

  console.log(`Setting CORS on bucket: ${BUCKET_NAME}`);
  console.log("Config:", JSON.stringify(CORS_CONFIG, null, 2));

  await bucket.setCorsConfiguration(CORS_CONFIG);

  const [metadata] = await bucket.getMetadata();
  console.log("\nCORS applied successfully.");
  console.log("Current CORS:", JSON.stringify(metadata.cors, null, 2));
}

setCors().catch((err) => {
  console.error("Failed to set CORS:", err.message);
  process.exit(1);
});
