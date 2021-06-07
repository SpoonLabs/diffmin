package com.diffmin;

import static org.junit.jupiter.api.Assertions.*;

import com.diffmin.util.SpoonUtil;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtCompilationUnit;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtType;
import spoon.reflect.path.CtPathStringBuilder;
import spoon.reflect.path.CtRole;
import spoon.reflect.visitor.CtScanner;

/** Unit test for simple App. */
public class AppTest {
    public static final Path RESOURCES_BASE_DIR = Paths.get("src/test/resources");

    public static final Path PURE_DELETE_PATCHES = RESOURCES_BASE_DIR.resolve("delete");

    public static final Path PURE_UPDATE_PATCHES = RESOURCES_BASE_DIR.resolve("update");

    public static final Path PURE_INSERT_PATCHES = RESOURCES_BASE_DIR.resolve("insert");

    public static final Path PURE_MOVE_PATCHES = RESOURCES_BASE_DIR.resolve("move");

    public static final Path MIX_OPERATION_PATCHES = RESOURCES_BASE_DIR.resolve("mix-operation");

    private static final String PREV_PREFIX = "PREV";

    private static final String NEW_PREFIX = "NEW";

    private static final String TEST_METADATA = "new_revision_paths";

    private static Stream<? extends Arguments> getArgumentSourceStream(
            File testDir, Function<File, TestResources> sourceGetter) {
        return Arrays.stream(testDir.listFiles())
                .filter(File::isDirectory)
                .map(sourceGetter)
                .map(Arguments::of);
    }

    /** Class to provide test resources. */
    public static class TestResources {
        public String parent;

        public Path prevPath;

        public Path newPath; // stylised new

        public Path newRevisionPaths;

        /**
         * Constructor of {@link TestResources}.
         *
         * @param prevPath path of the previous version of a file
         * @param newPath path of the new version of a file
         * @param parent name of the directory containing the two files
         * @param newRevisionPaths path to the file which contains {@link spoon.reflect.path.CtPath}
         *     corresponding to elements in latest revision
         */
        TestResources(Path prevPath, Path newPath, String parent, Path newRevisionPaths) {
            this.prevPath = prevPath;
            this.newPath = newPath;
            this.parent = parent;
            this.newRevisionPaths = newRevisionPaths;
        }

        /**
         * Resolve files inside a directory.
         *
         * @param testDir Directory containing test files
         * @return instance of {@link TestResources}
         */
        public static TestResources fromTestDirectory(File testDir) {
            String parent = testDir.getName();
            Path prevPath = getFilepathByPrefix(testDir, PREV_PREFIX);
            Path newPath = getFilepathByPrefix(testDir, NEW_PREFIX);
            Path testMetadata = getFilepathByPrefix(testDir, TEST_METADATA);
            return new TestResources(prevPath, newPath, parent, testMetadata);
        }

        /**
         * Returns test resource with the matching prefix.
         *
         * @param dir Directory where the test resources are located
         * @param prefix Prefix of the test resource
         * @return {@link Path} to the test resource
         */
        private static Path getFilepathByPrefix(File dir, String prefix) {
            return Arrays.stream(dir.listFiles())
                    .filter(f -> f.getName().startsWith(prefix))
                    .findFirst()
                    .map(File::toPath)
                    .orElseThrow(
                            () ->
                                    new RuntimeException(
                                            String.format(
                                                    "Expected file with prefix '%s' in directory '%s'",
                                                    prefix, dir)));
        }

        @Override
        public String toString() {
            return parent;
        }
    }

    /** Provides test sources for scenarios where only update patches are applied. */
    public static class PureUpdatePatches implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext extensionContext) {
            return getArgumentSourceStream(
                    PURE_UPDATE_PATCHES.toFile(), TestResources::fromTestDirectory);
        }
    }

    /** Provides test sources for scenarios where only delete patches are applied. */
    public static class PureDeletePatches implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext extensionContext) {
            return getArgumentSourceStream(
                    PURE_DELETE_PATCHES.toFile(), TestResources::fromTestDirectory);
        }
    }

    /** Provides test sources for scenarios where only insert patches are applied. */
    public static class PureInsertPatches implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext extensionContext) {
            return getArgumentSourceStream(
                    PURE_INSERT_PATCHES.toFile(), TestResources::fromTestDirectory);
        }
    }

    /** Provides test sources for scenarios where only move patches are applied. */
    public static class PureMovePatches implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext extensionContext) {
            return getArgumentSourceStream(
                    PURE_MOVE_PATCHES.toFile(), TestResources::fromTestDirectory);
        }
    }

    /** Provides test sources for scenarios where a mix of operations is applied. */
    public static class MixOperationPatches implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext extensionContext) {
            return getArgumentSourceStream(
                    MIX_OPERATION_PATCHES.toFile(), TestResources::fromTestDirectory);
        }
    }

    @ParameterizedTest
    @ArgumentsSource(PureUpdatePatches.class)
    void should_apply_pure_update_patches(TestResources sources) throws Exception {
        runTests(sources);
    }

    @ParameterizedTest
    @ArgumentsSource(PureDeletePatches.class)
    void should_apply_pure_delete_patches(TestResources sources) throws Exception {
        runTests(sources);
    }

    @ParameterizedTest
    @ArgumentsSource(PureInsertPatches.class)
    void should_apply_pure_insert_patches(TestResources sources) throws Exception {
        runTests(sources);
    }

    @ParameterizedTest
    @ArgumentsSource(PureMovePatches.class)
    void should_apply_pure_move_patches(TestResources sources) throws Exception {
        runTests(sources);
    }

    @ParameterizedTest
    @ArgumentsSource(MixOperationPatches.class)
    void should_apply_mix_operation_patches(TestResources sources) throws Exception {
        runTests(sources);
    }

    /** Scanner for checking source file of each element. */
    static class ElementSourceFileChecker extends CtScanner {
        private final Set<CtElement> newRevisions;

        ElementSourceFileChecker(List<CtElement> newRevisions) {
            this.newRevisions = Collections.newSetFromMap(new IdentityHashMap<>());
            this.newRevisions.addAll(newRevisions);
        }

        @Override
        public void scan(CtElement element) {
            if (!skipAssertionCheck(element)) {
                if (newRevisions.stream().anyMatch(modifiedElement -> modifiedElement == element)
                        || doesElementBelongToModifiedSet(element)) {
                    assertTrue(
                            doesElementBelongToSpecifiedFile(element, AppTest.NEW_PREFIX),
                            "Element should originate from new file but does not");
                } else {
                    assertTrue(
                            doesElementBelongToSpecifiedFile(element, AppTest.PREV_PREFIX),
                            "Element should originate from prev file but does not");
                }
            }
            super.scan(element);
        }

        private static boolean doesElementBelongToSpecifiedFile(
                CtElement element, String filePathPrefix) {
            return element.getPosition().getFile().getName().startsWith(filePathPrefix);
        }

        private static boolean doesElementBelongToModifiedSet(CtElement element) {
            if (element.getRoleInParent() == CtRole.THROWN) {
                return ((CtExecutable<?>) element.getParent())
                        .getThrownTypes().stream()
                                .anyMatch(
                                        thrownType ->
                                                doesElementBelongToSpecifiedFile(
                                                        thrownType, AppTest.NEW_PREFIX));
            }
            return false;
        }

        private boolean isChildOfInsertedPath(CtElement element) {
            return newRevisions.stream().anyMatch(element::hasParent);
        }

        private boolean skipAssertionCheck(CtElement element) {
            if (element == null
                    || element.isImplicit()
                    || !element.getPosition().isValidPosition()) {
                return true;
            }
            return isChildOfInsertedPath(element);
        }
    }

    private static void runTests(TestResources sources) throws Exception {
        File f1 = sources.prevPath.toFile();
        File f2 = sources.newPath.toFile();

        // check the structure
        CtModel patchedCtModel = Main.patchAndGenerateModel(f1, f2);
        CtModel expectedModel = SpoonUtil.buildModel(sources.newPath.toFile());
        Optional<CtType<?>> firstType = expectedModel.getAllTypes().stream().findFirst();
        if (firstType.isEmpty()) {
            assertTrue(
                    patchedCtModel.getAllTypes().stream().findFirst().isEmpty(),
                    "Patched prev file is not empty");
        } else {
            CtType<?> retrievedFirstType = firstType.get();
            CtCompilationUnit cu =
                    retrievedFirstType
                            .getFactory()
                            .CompilationUnit()
                            .getOrCreate(retrievedFirstType);
            String patchedProgram = SpoonUtil.displayModifiedModel(patchedCtModel);
            assertEquals(cu.prettyprint(), patchedProgram, "Prev file was not patched correctly");
        }

        // check the root origination of each element
        List<CtElement> newRevisions =
                Files.readAllLines(sources.newRevisionPaths).stream()
                        .map(pathString -> new CtPathStringBuilder().fromString(pathString))
                        .map(path -> path.evaluateOn(patchedCtModel.getRootPackage()).get(0))
                        .collect(Collectors.toList());
        new ElementSourceFileChecker(newRevisions).scan(patchedCtModel.getRootPackage());
    }
}
