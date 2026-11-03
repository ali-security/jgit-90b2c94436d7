/*
 * Copyright (C) 2023 Thomas Wolf <twolf@apache.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.symlinks;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.MergeResult.MergeStatus;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheCheckout;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.junit.RepositoryTestCase;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FileUtils;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Checks that files are never written through a symbolic link that sits in
 * the place of one of their parent directories in the working tree.
 * <p>
 * On a case-insensitive file system a symlink "A" pointing to ".git" makes a
 * later write of "a/b" end up in ".git/b" (see {@link DirectoryTest}). These
 * tests reproduce the same situation on any file system that supports
 * symlinks by putting such a link directly into the working tree before the
 * operation that writes the file.
 */
public class SymlinkParentDirectoryTest extends RepositoryTestCase {

	@BeforeClass
	public static void checkPrecondition() throws Exception {
		Assume.assumeTrue(FS.DETECTED.supportsSymlinks());
	}

	private void link(String path, String target) throws Exception {
		File f = new File(trash, path);
		FileUtils.delete(f, FileUtils.RECURSIVE | FileUtils.SKIP_MISSING);
		FS.DETECTED.createSymLink(f, target);
		assertTrue(path + " should be a symlink",
				Files.isSymbolicLink(f.toPath()));
	}

	private void assertNotInGitDir(String name) {
		File f = new File(db.getDirectory(), name);
		assertFalse(".git/" + name + " should not exist",
				Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS));
	}

	private void assertDirectory(String path) {
		File f = new File(trash, path);
		assertTrue(path + " should be a directory",
				Files.isDirectory(f.toPath(), LinkOption.NOFOLLOW_LINKS));
	}

	private void assertFileContent(String path, String expected)
			throws Exception {
		File f = new File(trash, path);
		assertTrue(path + " should be a file",
				Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS));
		assertEquals(expected, read(f));
	}

	@Test
	public void testResetHard() throws Exception {
		TestRepository<Repository> repo = new TestRepository<>(db);
		RevCommit commit = repo.commit(repo.tree(
				repo.file("a/b", repo.blob("test")),
				repo.file("x/y/z", repo.blob("test2"))));
		// Direct parent and a grand-parent of the files to be checked out
		// are symlinks to the git directory.
		link("a", ".git");
		link("x", ".git");
		try (Git git = new Git(db)) {
			git.reset().setMode(ResetType.HARD).setRef(commit.name()).call();
		}
		assertNotInGitDir("b");
		assertNotInGitDir("y");
		assertDirectory("a");
		assertFileContent("a/b", "test");
		assertDirectory("x");
		assertDirectory("x/y");
		assertFileContent("x/y/z", "test2");
	}

	@Test
	public void testCheckoutPathFromIndex() throws Exception {
		writeTrashFile("a/b", "test");
		try (Git git = new Git(db)) {
			git.add().addFilepattern("a/b").call();
			git.commit().setMessage("initial").call();
			link("a", ".git");
			git.checkout().addPath("a/b").call();
		}
		assertNotInGitDir("b");
		assertDirectory("a");
		assertFileContent("a/b", "test");
	}

	@Test
	public void testCheckoutPathFromCommit() throws Exception {
		writeTrashFile("a/b", "test");
		try (Git git = new Git(db)) {
			git.add().addFilepattern("a/b").call();
			git.commit().setMessage("initial").call();
			link("a", ".git");
			git.checkout().setStartPoint("HEAD").addPath("a/b").call();
		}
		assertNotInGitDir("b");
		assertDirectory("a");
		assertFileContent("a/b", "test");
	}

	@Test
	public void testCheckoutEntry() throws Exception {
		writeTrashFile("a/b", "test");
		try (Git git = new Git(db)) {
			git.add().addFilepattern("a/b").call();
		}
		link("a", ".git");
		DirCache dc = db.readDirCache();
		DirCacheEntry entry = dc.getEntry("a/b");
		assertNotNull(entry);
		try (ObjectReader reader = db.newObjectReader()) {
			DirCacheCheckout.checkoutEntry(db, entry, reader, false, null);
		}
		assertNotInGitDir("b");
		assertDirectory("a");
		assertFileContent("a/b", "test");
	}

	@Test
	public void testMerge() throws Exception {
		writeTrashFile("a/z", "1\n2\n3\n4\n5\n");
		writeTrashFile("c/f", "f\n");
		writeTrashFile("c/g", "g\n");
		try (Git git = new Git(db)) {
			git.add().addFilepattern(".").call();
			git.commit().setMessage("base").call();
			git.branchCreate().setName("side").call();
			git.checkout().setName("side").call();
			writeTrashFile("a/z", "1\n2\n3\n4\n5x\n");
			writeTrashFile("c/f", "f2\n");
			git.add().addFilepattern(".").call();
			RevCommit side = git.commit().setMessage("side").call();
			git.checkout().setName("master").call();
			writeTrashFile("a/z", "1x\n2\n3\n4\n5\n");
			writeTrashFile("c/g", "g2\n");
			git.add().addFilepattern(".").call();
			git.commit().setMessage("master").call();
			// "a/z" gets content-merged, "c/f" is checked out from "side"
			link("a", ".git");
			link("c", ".git");
			MergeResult result;
			try {
				result = git.merge().include(side).setMessage("merged")
						.call();
			} finally {
				// Check even if the merge failed: the merger writes files
				// before the final checkout of the merge result.
				assertNotInGitDir("z");
				assertNotInGitDir("f");
			}
			assertEquals(MergeStatus.MERGED, result.getMergeStatus());
		}
		assertDirectory("a");
		assertFileContent("a/z", "1x\n2\n3\n4\n5x\n");
		assertDirectory("c");
		assertFileContent("c/f", "f2\n");
	}

	@Test
	public void testStashApplyUntracked() throws Exception {
		writeTrashFile("q", "q");
		try (Git git = new Git(db)) {
			git.add().addFilepattern("q").call();
			git.commit().setMessage("initial").call();
			writeTrashFile("a/b", "untracked");
			RevCommit stash = git.stashCreate().setIncludeUntracked(true)
					.call();
			assertNotNull(stash);
			link("a", ".git");
			git.stashApply().setStashRef(stash.name()).call();
		}
		assertNotInGitDir("b");
		assertDirectory("a");
		assertFileContent("a/b", "untracked");
	}

	@Test
	public void testApplyAdd() throws Exception {
		link("a", ".git");
		try (Git git = new Git(db);
				InputStream patch = getClass()
						.getResourceAsStream("dirtest.patch")) {
			assertNotNull(patch);
			git.apply().setPatch(patch).call();
		}
		assertNotInGitDir("b");
		assertDirectory("a");
		assertTrue("a/b should exist",
				Files.isRegularFile(new File(trash, "a/b").toPath(),
						LinkOption.NOFOLLOW_LINKS));
	}

	@Test
	public void testApplyRename() throws Exception {
		writeTrashFile("q", "test");
		link("a", ".git");
		String patch = "diff --git a/q b/a/b\n" //
				+ "similarity index 100%\n" //
				+ "rename from q\n" //
				+ "rename to a/b\n";
		try (Git git = new Git(db)) {
			git.apply().setPatch(
					new ByteArrayInputStream(patch.getBytes(UTF_8))).call();
		}
		assertNotInGitDir("b");
		assertDirectory("a");
		assertFileContent("a/b", "test");
		assertFalse("q should have been renamed",
				new File(trash, "q").exists());
	}
}
