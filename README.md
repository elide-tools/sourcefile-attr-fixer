
## source file fixer

sometimes there is a case where some JVM `.class` file, deep within some `.jar`, deep within some classpath or modulepath, is missing its `SourceFile` attribute, or has that attribute set to the value `"stripped"` because it has, well, been stripped.

later, when we try to use that attribute in various ways, tools expect it to be a filename, and so they tend to split by `'.'` and take `split[1]` without thinking too much about it.

### so this tool fixes that

by looking for such illegal values, and replacing them either with (1) a sensible file name derived from the class name, or (2) a generated (but always deterministic) class name. either option fix downstream tooling which expects a filename.

## usage

1. You can use it from Docker:
```
# Note that your JAR will be modified, so copy it first
cp -fv some-jar ./my-cool.jar
docker run --rm -v $(PWD):/workspace -w /workspace -it ghcr.io/elide-tools/sourcefile-fixer my-cool.jar
```

2. You can clone and build it natively:
```
git clone ...
elide install
elide build
cp -fv some-jar ./my-cool.jar
./.dev/artifacts/native-image/elide-source-file-fixer/elide-source-file-fixer ./my-cool.jar
```

