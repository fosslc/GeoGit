/* Copyright (c) 2013 OpenPlans. All rights reserved.
 * This code is licensed under the BSD New License, available at the root
 * application directory.
 */
package org.geogit.osm.internal;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.geogit.api.AbstractGeoGitOp;
import org.geogit.api.ObjectId;
import org.geogit.api.porcelain.AddOp;
import org.geogit.api.porcelain.CommitOp;
import org.geogit.osm.internal.log.AddOSMLogEntry;
import org.geogit.osm.internal.log.OSMLogEntry;
import org.geogit.osm.internal.log.OSMMappingLogEntry;
import org.geogit.osm.internal.log.WriteOSMFilterFile;
import org.geogit.osm.internal.log.WriteOSMMappingEntries;
import org.geogit.repository.WorkingTree;
import org.geotools.util.NullProgressListener;
import org.opengis.feature.Feature;
import org.openstreetmap.osmosis.core.container.v0_6.EntityContainer;
import org.openstreetmap.osmosis.core.domain.v0_6.Bound;
import org.openstreetmap.osmosis.core.domain.v0_6.Entity;
import org.openstreetmap.osmosis.core.domain.v0_6.Node;
import org.openstreetmap.osmosis.core.domain.v0_6.Relation;
import org.openstreetmap.osmosis.core.domain.v0_6.Way;
import org.openstreetmap.osmosis.core.domain.v0_6.WayNode;
import org.openstreetmap.osmosis.core.task.v0_6.RunnableSource;
import org.openstreetmap.osmosis.core.task.v0_6.Sink;
import org.openstreetmap.osmosis.xml.common.CompressionMethod;
import org.openstreetmap.osmosis.xml.v0_6.XmlReader;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import com.google.common.io.Closeables;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.Point;

import crosby.binary.osmosis.OsmosisReader;

/**
 * Imports data from OSM, whether from a URL that represents an endpoint that supports the OSM
 * overpass api, or from a file with OSM data
 * 
 */

public class OSMImportOp extends AbstractGeoGitOp<Optional<OSMDownloadReport>> {

    /**
     * The filter to use if calling the overpass API
     */
    private String filter;

    /**
     * The URL of file to use for importing
     */
    private String urlOrFilepath;

    private File downloadFile;

    private boolean keepFile;

    private boolean add;

    private Mapping mapping;

    private boolean noRaw;

    private String message;

    /**
     * Sets the filter to use. It uses the overpass Query Language
     * 
     * @param filter the filter to use
     * @return {@code this}
     */
    public OSMImportOp setFilter(String filter) {
        this.filter = filter;
        return this;
    }

    /**
     * Sets the message to use if a commit is created
     * 
     * @param message the commit message
     * @return {@code this}
     */
    public OSMImportOp setMessage(String message) {
        this.message = message;
        return this;
    }

    /**
     * Sets the file to which download the response of the OSM server
     * 
     * @param saveFile
     * @return {@code this}
     */
    public OSMImportOp setDownloadFile(File saveFile) {
        this.downloadFile = saveFile;
        return this;
    }

    /**
     * Sets whether, in the case of using a mapping, the raw unmapped data should also be imported
     * or not
     * 
     * @param noRaw True if the raw data should not be imported, but only the mapped data
     * @return {@code this}
     */
    public OSMImportOp setNoRaw(boolean noRaw) {
        this.noRaw = noRaw;
        return this;
    }

    public OSMImportOp setMapping(Mapping mapping) {
        this.mapping = mapping;
        return this;
    }

    /**
     * Sets whether to keep the downloaded file or not
     * 
     * @param keepFiles
     * @return {@code this}
     */
    public OSMImportOp setKeepFile(boolean keepFile) {
        this.keepFile = keepFile;
        return this;
    }

    /**
     * Sets whether to add new data to existing one, or to remove existing data before importing
     * 
     * @param add
     * @return {@code this}
     */
    public OSMImportOp setAdd(boolean add) {
        this.add = add;
        return this;
    };

    /**
     * Sets the source of OSM data. Can be the URL of an endpoint supporting the overpass API, or a
     * filepath
     * 
     * @param urlOrFilepath
     * @return{@code this}
     */
    public OSMImportOp setDataSource(String urlOrFilepath) {
        this.urlOrFilepath = urlOrFilepath;
        return this;
    }

    @SuppressWarnings("deprecation")
    @Override
    public Optional<OSMDownloadReport> call() {

        checkNotNull(urlOrFilepath);

        ObjectId oldTreeId = getWorkTree().getTree().getId();

        final File osmDataFile;
        if (urlOrFilepath.startsWith("http")) {
            osmDataFile = downloadFile(this.downloadFile);
        } else {
            osmDataFile = new File(urlOrFilepath);
            Preconditions.checkArgument(osmDataFile.exists(), "File does not exist: "
                    + urlOrFilepath);
        }

        getProgressListener().setDescription("Importing into GeoGit repo...");

        EntityConverter converter = new EntityConverter();

        OSMDownloadReport report = parseDataFileAndInsert(osmDataFile, converter);

        if (urlOrFilepath.startsWith("http") && !keepFile) {
            osmDataFile.delete();
        }

        if (report != null) {
            ObjectId newTreeId = getWorkTree().getTree().getId();
            if (!noRaw) {
                if (mapping != null || filter != null) {
                    getProgressListener().setDescription("Staging features...");
                    command(AddOp.class).setProgressListener(getProgressListener()).call();
                    getProgressListener().setDescription("Committing features...");
                    command(CommitOp.class).setMessage(message)
                            .setProgressListener(getProgressListener()).call();
                    OSMLogEntry entry = new OSMLogEntry(newTreeId, report.getLatestChangeset(),
                            report.getLatestTimestamp());
                    command(AddOSMLogEntry.class).setEntry(entry).call();
                    if (filter != null) {
                        command(WriteOSMFilterFile.class).setEntry(entry).setFilterCode(filter)
                                .call();
                    }
                    if (mapping != null) {
                        command(WriteOSMMappingEntries.class).setMapping(mapping)
                                .setMappingLogEntry(new OSMMappingLogEntry(oldTreeId, newTreeId))
                                .call();
                    }
                }
            }
        }

        return Optional.fromNullable(report);

    }

    private File downloadFile(@Nullable File destination) {

        getProgressListener().setDescription("Downloading data...");
        checkNotNull(filter);
        OSMDownloader downloader = new OSMDownloader(urlOrFilepath, getProgressListener());

        if (destination == null) {
            try {
                destination = File.createTempFile("osm-geogit", ".xml");
            } catch (IOException e) {
                Throwables.propagate(e);
            }
        } else {
            destination = destination.getAbsoluteFile();
        }

        try {
            File file = downloader.download(filter, destination);
            return file;
        } catch (Exception e) {
            throw Throwables.propagate(Throwables.getRootCause(e));
        }
    }

    private OSMDownloadReport parseDataFileAndInsert(File file, final EntityConverter converter) {

        final boolean pbf = file.getName().endsWith(".pbf");
        final CompressionMethod compression = resolveCompressionMethod(file);

        RunnableSource reader;
        InputStream dataIn = null;
        if (pbf) {
            try {
                dataIn = new BufferedInputStream(new FileInputStream(file), 1024 * 1024);
                reader = new OsmosisReader(dataIn);
            } catch (Exception e) {
                Closeables.closeQuietly(dataIn);
                throw Throwables.propagate(e);
            }
        } else {
            reader = new XmlReader(file, true, compression);
        }

        final WorkingTree workTree = getWorkTree();
        if (!add) {
            workTree.delete(OSMUtils.NODE_TYPE_NAME);
            workTree.delete(OSMUtils.WAY_TYPE_NAME);
        }

        final int queueCapacity = 10 * 1000;
        final int timeout = 1;
        final TimeUnit timeoutUnit = TimeUnit.SECONDS;
        QueueIterator<Feature> iterator = new QueueIterator<Feature>(queueCapacity, timeout,
                timeoutUnit);

        try {
            PointCache pointCache = new PointCache(workTree.getTree(), getCommandLocator(),
                    getIndex().getDatabase());
            ConvertAndImportSink sink = new ConvertAndImportSink(converter, iterator, pointCache);
            reader.setSink(sink);

            Thread readerThread = new Thread(reader, "osm-import-reader-thread");
            readerThread.start();

            Function<Feature, String> parentTreePathResolver = new Function<Feature, String>() {
                @Override
                public String apply(Feature input) {
                    if (input instanceof MappedFeature) {
                        return ((MappedFeature) input).getPath();
                    }
                    return input.getType().getName().getLocalPart();
                }
            };

            workTree.insert(parentTreePathResolver, iterator, new NullProgressListener(), null,
                    null);

            while (readerThread.isAlive()) {
                try {
                    readerThread.join();
                } catch (InterruptedException e) {
                    return null;
                }
            }

            if (sink.getCount() == 0) {
                throw new EmptyOSMDownloadException();
            }

            OSMDownloadReport report = new OSMDownloadReport(sink.getCount(),
                    sink.getUnprocessedCount(), sink.getLatestChangeset(),
                    sink.getLatestTimestamp());
            return report;
        } finally {
            Closeables.closeQuietly(dataIn);
        }
    }

    private CompressionMethod resolveCompressionMethod(File file) {
        String fileName = file.getName();
        if (fileName.endsWith(".gz")) {
            return CompressionMethod.GZip;
        } else if (fileName.endsWith(".bz2")) {
            return CompressionMethod.BZip2;
        }
        return CompressionMethod.None;
    }

    /**
     * A sink that processes OSM entities by converting them to GeoGit features and inserting them
     * into the repository working tree
     * 
     */
    class ConvertAndImportSink implements Sink {

        int count = 0;

        int unableToProcessCount = 0;

        private EntityConverter converter;

        private long latestChangeset;

        private long latestTimestamp;

        private PointCache pointCache;

        private QueueIterator<Feature> target;

        public ConvertAndImportSink(EntityConverter converter, QueueIterator<Feature> target,
                PointCache pointCache) {
            super();
            this.converter = converter;
            this.target = target;
            this.pointCache = pointCache;
            this.latestChangeset = 0;
            this.latestTimestamp = 0;
        }

        public long getUnprocessedCount() {
            return unableToProcessCount;
        }

        public long getCount() {
            return count;
        }

        @Override
        public void complete() {
            getProgressListener().progress(count);
            target.finish();
        }

        @Override
        public void process(EntityContainer entityContainer) {
            Entity entity = entityContainer.getEntity();
            if (++count % 10 == 0) {
                getProgressListener().progress(count);
            }
            latestChangeset = Math.max(latestChangeset, entity.getChangesetId());
            latestTimestamp = Math.max(latestTimestamp, entity.getTimestamp().getTime());
            if (entity instanceof Relation || entity instanceof Bound) {
                return;
            }
            Geometry geom = parseGeometry(entity);
            if (geom != null) {

                @Nullable
                Feature feature = converter.toFeature(entity, geom);
                if (mapping != null && feature != null) {
                    Optional<MappedFeature> mapped = mapping.map(feature);
                    if (mapped.isPresent()) {
                        MappedFeature mappedFeature = mapped.get();
                        target.put(mappedFeature);
                    }
                }
                if (feature == null || noRaw) {
                    return;
                }
                target.put(feature);

            }
        }

        /**
         * returns the latest timestamp of all the entities processed so far
         * 
         * @return
         */
        public long getLatestTimestamp() {
            return latestTimestamp;
        }

        /**
         * returns the id of the latest changeset of all the entities processed so far
         * 
         * @return
         */
        public long getLatestChangeset() {
            return latestChangeset;
        }

        public boolean hasProcessedEntities() {
            return latestChangeset != 0;
        }

        @Override
        public void release() {
        }

        @Override
        public void initialize(Map<String, Object> map) {
        }

        private final GeometryFactory GEOMF = new GeometryFactory();

        /**
         * Returns the geometry corresponding to an entity. A point in the case of a node, a
         * lineString for a way. Returns null if it could not create the geometry.
         * 
         * This will be the case if the entity is a way but the corresponding nodes cannot be found,
         * and also if the entity is of a type other than Node of Way
         * 
         * @param entity the entity to extract the geometry from
         * @return
         */
        protected Geometry parseGeometry(Entity entity) {

            if (entity instanceof Node) {
                Node node = ((Node) entity);
                Coordinate coord = new Coordinate(node.getLongitude(), node.getLatitude());
                Point pt = GEOMF.createPoint(coord);
                pointCache.put(Long.valueOf(node.getId()), coord);
                return pt;
            }

            final Way way = (Way) entity;
            final List<WayNode> nodes = way.getWayNodes();

            List<Coordinate> coordinates = Lists.newArrayList();
            for (WayNode node : nodes) {
                long nodeId = node.getNodeId();
                Coordinate coord = pointCache.get(nodeId);
                if (coord != null) {
                    coordinates.add(coord);
                }
            }
            if (coordinates.size() < 2) {
                unableToProcessCount++;
                return null;
            }

            return GEOMF.createLineString(coordinates.toArray(new Coordinate[coordinates.size()]));
        }
    }

}
