/* Copyright (c) 2013 OpenPlans. All rights reserved.
 * This code is licensed under the BSD New License, available at the root
 * application directory.
 */
package org.geogit.api.porcelain;

import static com.google.common.base.Preconditions.checkState;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import org.geogit.api.AbstractGeoGitOp;
import org.geogit.api.CommitBuilder;
import org.geogit.api.ObjectId;
import org.geogit.api.Platform;
import org.geogit.api.Ref;
import org.geogit.api.RevCommit;
import org.geogit.api.SymRef;
import org.geogit.api.plumbing.FindCommonAncestor;
import org.geogit.api.plumbing.ForEachRef;
import org.geogit.api.plumbing.RefParse;
import org.geogit.api.plumbing.UpdateRef;
import org.geogit.api.plumbing.UpdateSymRef;
import org.geogit.api.porcelain.ResetOp.ResetMode;
import org.geogit.repository.Repository;
import org.geogit.storage.GraphDatabase;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.inject.Inject;

/**
 * Operation to squash commits into one.
 */
public class SquashOp extends AbstractGeoGitOp<ObjectId> {

    private RevCommit since;

    private RevCommit until;

    private Repository repository;

    private Platform platform;

    private String message;

    private GraphDatabase graphDb;

    /**
     * Constructs a new {@code SquashOp} with the given {@link Repository}.
     * 
     * @param repository the repository where the commits to squash are found
     */
    @Inject
    public SquashOp(final Repository repository, Platform platform, GraphDatabase graphDb) {
        this.repository = repository;
        this.platform = platform;
        this.graphDb = graphDb;
    }

    /**
     * Indicates the first commit to squash. If no message is provided, the message from this commit
     * will be used
     * 
     * @param the first (oldest) commit to squash
     * @return {@code this}
     */
    public SquashOp setSince(final RevCommit since) {
        this.since = since;
        return this;
    }

    /**
     * Indicates the last commit to squash
     * 
     * @param the last (most recent) commit to squash
     * @return {@code this}
     */
    public SquashOp setUntil(RevCommit until) {
        this.until = until;
        return this;
    }

    /**
     * Indicates the message to use for the commit. If null, the message from the 'since' commit
     * will be used
     * 
     * @param the message to use for the commit
     * @return {@code this}
     */
    public SquashOp setMessage(String message) {
        this.message = message;
        return this;
    }

    /**
     * Executes the squash operation.
     * 
     * @return the new head after modifying the history squashing commits
     * @see org.geogit.api.AbstractGeoGitOp#call()
     */
    @Override
    public ObjectId call() {

        Preconditions.checkNotNull(since);
        Preconditions.checkNotNull(until);

        final Optional<Ref> currHead = command(RefParse.class).setName(Ref.HEAD).call();
        Preconditions.checkState(currHead.isPresent(), "Repository has no HEAD, can't rebase.");
        Preconditions.checkState(currHead.get() instanceof SymRef,
                "Can't rebase from detached HEAD");
        final SymRef headRef = (SymRef) currHead.get();
        final String currentBranch = headRef.getTarget();

        long staged = getIndex().countStaged(null);
        long unstaged = getWorkTree().countUnstaged(null);
        Preconditions.checkState((staged == 0 && unstaged == 0),
                "You must have a clean working tree and index to perform a squash.");

        Optional<RevCommit> ancestor = command(FindCommonAncestor.class).setLeft(since)
                .setRight(until).call();
        Preconditions.checkArgument(ancestor.isPresent(),
                "'since' and 'until' command are not in the same branch");
        Preconditions
                .checkArgument(ancestor.get().equals(since), "Commits provided in wrong order");

        Preconditions.checkArgument(!since.getParentIds().isEmpty(),
                "'since' commit has no parents");

        // we get a a list of commits to apply on top of the squashed commits
        List<RevCommit> commits = getCommitsAfterUntil();

        ImmutableSet<Ref> refs = command(ForEachRef.class).setPrefixFilter("refs/heads").call();

        // we create a list of all secondary parents of those squashed commits, in case they are
        // merge commits. The resulting commit will have all these parents
        //
        // While iterating the set of commits to squash, we check that there are no branch starting
        // points among them. Any commit with more than one child causes an exception to be thrown,
        // since the squash operation does not support squashing those commits
        Iterator<RevCommit> toSquash = command(LogOp.class).setSince(since.getParentIds().get(0))
                .setUntil(until.getId()).setFirstParentOnly(true).call();
        List<ObjectId> parents = Lists.newArrayList();
        while (toSquash.hasNext()) {
            RevCommit commit = toSquash.next();
            Preconditions
                    .checkArgument(
                            graphDb.getChildren(commit.getId()).size() < 2,
                            "The commits to squash include a branch starting point. Squashing that type of commit is not supported.");
            for (Ref ref : refs) {
                // In case a branch has been created but no commit has been made on it and the
                // starting commit has just one child
                Preconditions
                        .checkArgument(
                                !ref.getObjectId().equals(commit.getId())
                                        || ref.getObjectId().equals(currHead.get().getObjectId())
                                        || commit.getParentIds().size() > 1,
                                "The commits to squash include a branch starting point. Squashing that type of commit is not supported.");
            }
            ImmutableList<ObjectId> parentIds = commit.getParentIds();
            for (int i = 1; i < parentIds.size(); i++) {
                parents.add(parentIds.get(i));
            }
        }

        // We do the same check in the children commits
        for (RevCommit commit : commits) {
            Preconditions
                    .checkArgument(
                            graphDb.getChildren(commit.getId()).size() < 2,
                            "The commits after the ones to squash include a branch starting point. This scenario is not supported.");
            for (Ref ref : refs) {
                // In case a branch has been created but no commit has been made on it
                Preconditions
                        .checkArgument(
                                !ref.getObjectId().equals(commit.getId())
                                        || ref.getObjectId().equals(currHead.get().getObjectId())
                                        || commit.getParentIds().size() > 1,
                                "The commits after the ones to squash include a branch starting point. This scenario is not supported.");
            }
            ImmutableList<ObjectId> parentIds = commit.getParentIds();
            for (int i = 1; i < parentIds.size(); i++) {
                parents.add(parentIds.get(i));
            }
        }

        // In case a branch has been created but

        ObjectId newHead;
        // rewind the head
        newHead = since.getParentIds().get(0);
        command(ResetOp.class).setCommit(Suppliers.ofInstance(newHead)).setMode(ResetMode.HARD)
                .call();

        // add the current HEAD as first parent of the resulting commit
        parents.add(0, newHead);

        // Create new commit
        ObjectId endTree = until.getTreeId();
        CommitBuilder builder = new CommitBuilder(until);
        builder.setParentIds(parents);
        builder.setTreeId(endTree);
        if (message == null) {
            message = since.getMessage();
        }
        long timestamp = platform.currentTimeMillis();
        builder.setMessage(message);
        builder.setCommitter(resolveCommitter());
        builder.setCommitterEmail(resolveCommitterEmail());
        builder.setCommitterTimestamp(timestamp);
        builder.setCommitterTimeZoneOffset(platform.timeZoneOffset(timestamp));
        builder.setAuthorTimestamp(until.getAuthor().getTimestamp());

        RevCommit newCommit = builder.build();
        repository.getObjectDatabase().put(newCommit);

        newHead = newCommit.getId();
        ObjectId newTreeId = newCommit.getTreeId();

        command(UpdateRef.class).setName(currentBranch).setNewValue(newHead).call();
        command(UpdateSymRef.class).setName(Ref.HEAD).setNewValue(currentBranch).call();

        getWorkTree().updateWorkHead(newTreeId);
        getIndex().updateStageHead(newTreeId);

        // now put the other commits after the squashed one
        newHead = addCommits(commits, currentBranch, newHead);

        return newHead;

    }

    private ObjectId addCommits(List<RevCommit> commits, String currentBranch, ObjectId head) {
        for (RevCommit commit : commits) {
            CommitBuilder builder = new CommitBuilder(commit);
            builder.setParentIds(Arrays.asList(head));
            builder.setTreeId(commit.getTreeId());
            long timestamp = platform.currentTimeMillis();
            builder.setCommitterTimestamp(timestamp);
            builder.setCommitterTimeZoneOffset(platform.timeZoneOffset(timestamp));

            RevCommit newCommit = builder.build();
            repository.getObjectDatabase().put(newCommit);
            head = newCommit.getId();
            ObjectId newTreeId = newCommit.getTreeId();

            command(UpdateRef.class).setName(currentBranch).setNewValue(head).call();
            command(UpdateSymRef.class).setName(Ref.HEAD).setNewValue(currentBranch).call();

            getWorkTree().updateWorkHead(newTreeId);
            getIndex().updateStageHead(newTreeId);
        }

        return head;
    }

    private List<RevCommit> getCommitsAfterUntil() {
        Iterator<RevCommit> commitIterator = command(LogOp.class).setSince(until.getId()).call();
        List<RevCommit> commits = Lists.newArrayList(commitIterator);
        return commits;
    }

    private String resolveCommitter() {
        final String key = "user.name";
        Optional<String> name = command(ConfigGet.class).setName(key).call();

        checkState(
                name.isPresent(),
                "%s not found in config. Use geogit config [--global] %s <your name> to configure it.",
                key, key);

        return name.get();
    }

    private String resolveCommitterEmail() {
        final String key = "user.email";
        Optional<String> email = command(ConfigGet.class).setName(key).call();

        checkState(
                email.isPresent(),
                "%s not found in config. Use geogit config [--global] %s <your email> to configure it.",
                key, key);

        return email.get();
    }

}
