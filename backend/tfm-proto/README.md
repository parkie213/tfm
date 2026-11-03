# tfm-proto

The protobuf and gRPC contract shared by the backend and frontend.
Schemas live in `src/main/proto/tfm/v1/`; `tfm.proto` declares the service RPCs and imports
the feature-specific messages.

Maven generates Java messages and gRPC stubs under `target/`. The frontend generates its
TypeScript messages from the same schemas. Edit the `.proto` sources, not generated code.
Preserve existing field numbers and reserve removed fields to avoid reusing their wire identifiers.

This module depends on protobuf and gRPC libraries, but has no dependency on the model,
feed adapters, or server. The server owns conversion between model values and wire messages.

Build Java messages and stubs from `backend/` with JDK 25 and Maven:

```sh
mvn -pl tfm-proto -am verify
```

After changing schemas, regenerate and check the frontend from `frontend/`:

```sh
npm run generate
npm run build
```

After deleting messages, use a clean Maven build to remove obsolete generated Java sources.
See [the backend module guide](../README.md) for the module layout.
