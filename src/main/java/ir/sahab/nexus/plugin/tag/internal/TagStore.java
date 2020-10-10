package ir.sahab.nexus.plugin.tag.internal;

import static org.sonatype.nexus.common.app.ManagedLifecycle.Phase.SCHEMAS;

import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx;
import ir.sahab.nexus.plugin.tag.internal.dto.Tag;
import ir.sahab.nexus.plugin.tag.internal.dto.TagDefinition;
import ir.sahab.nexus.plugin.tag.internal.exception.TagAlreadyExistsException;
import ir.sahab.nexus.plugin.tag.internal.exception.TagNotFoundException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;
import org.sonatype.nexus.common.app.ManagedLifecycle;
import org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport;
import org.sonatype.nexus.orient.DatabaseInstance;
import org.sonatype.nexus.orient.DatabaseInstanceNames;
import org.sonatype.nexus.orient.DatabaseManager;

/**
 * Acts as a facade for storing and retrieving tags into database.
 */
@Named("tagStore")
@ManagedLifecycle(phase = SCHEMAS)
@Singleton
public class TagStore extends StateGuardLifecycleSupport {

    private final Provider<DatabaseInstance> dbProvider;
    private final DatabaseManager databaseManager;
    private final TagEntityAdapter entityAdapter;

    @Inject
    public TagStore(@Named(TagDatabase.NAME) Provider<DatabaseInstance> dbProvider, DatabaseManager databaseManager,
            TagEntityAdapter entityAdapter) {
        this.dbProvider = dbProvider;
        this.databaseManager = databaseManager;
        this.entityAdapter = entityAdapter;
    }

    /**
     * Prior to v1.0.3, plugin didn't have a specific database and used first injected database. This method migrates
     * existing data in nexus database into tag database if exist. TODO: Remove migration in next major release.
     */
    @Override
    protected void doStart() {
        List<TagEntity> tagsToMigrate = new ArrayList<>();
        Set<String> cleanupDatabases = new HashSet<>();
        for (String databaseName : DatabaseInstanceNames.DATABASE_NAMES) {
            try (ODatabaseDocumentTx tx = databaseManager.instance(databaseName).acquire()) {
                if (tx.getMetadata().getSchema().existsClass(TagEntityAdapter.DB_CLASS)) {
                    cleanupDatabases.add(databaseName);
                    log.info("Tag class exists in {} db, all tags will be migrated into tag database.", databaseName);
                    List<TagEntity> tags =
                            StreamSupport.stream(entityAdapter.search(tx, Collections.emptyMap()).spliterator(), false)
                                    .collect(Collectors.toList());
                    tagsToMigrate.addAll(tags);
                }
            }
        }
        try (ODatabaseDocumentTx tx = dbProvider.get().connect()) {
            entityAdapter.register(tx);
            log.info("Tag entity adapter registered into {} db.", tx.getName());
            if (!tagsToMigrate.isEmpty()) {
                log.info("Importing {} tags from {} database.", tagsToMigrate.size(), cleanupDatabases);
                for (TagEntity tag : tagsToMigrate) {
                    if (!entityAdapter.findByName(tx, tag.getName()).isPresent()) {
                        entityAdapter.addEntity(tx, tag);
                    }
                }
                log.info("{} tags imported into tag database.", tagsToMigrate.size());
            }
        }
        for (String database : cleanupDatabases) {
            try (ODatabaseDocumentTx tx = databaseManager.instance(database).connect()) {
                tx.getMetadata().getSchema().dropClass(TagEntityAdapter.DB_CLASS);
                log.info("{} dropped in {} database.", TagEntityAdapter.DB_CLASS, database);
            }
        }
    }

    /**
     * @return tag with given name
     * @throws TagNotFoundException if tag does not exists
     */
    public Tag getByName(String name) {
        try (ODatabaseDocumentTx tx = dbProvider.get().acquire()) {
            return getTag(name, tx).toDto();
        }
    }

    /**
     * Searches for tags with given attributes and components.
     * @param attributes attributes to match
     * @param componentCriteria components criteria to match on resulting tags
     * @return list of found tags
     */
    public List<Tag> search(Map<String, String> attributes, List<ComponentSearchCriterion> componentCriteria) {
        try (ODatabaseDocumentTx tx = dbProvider.get().acquire().begin()) {
            return StreamSupport.stream(entityAdapter.search(tx, attributes).spliterator(), false)
                    .filter(tag -> tag.matches(componentCriteria))
                    .map(TagEntity::toDto)
                    .collect(Collectors.toList());
        }
    }


    /**
     * Creates tag with given definition if it does not already exists, otherwise
     * existing one will be update with the definition.
     *
     * @return created/updated tag
     */
    public Tag addOrUpdate(TagDefinition definition) {
        log.info("Adding or updating tag: {}", definition);
        try (ODatabaseDocumentTx tx = dbProvider.get().acquire().begin()) {
            Date currentDate = new Date();
            Optional<TagEntity> existing = entityAdapter.findByName(tx, definition.getName());
            TagEntity entity = existing.orElseGet(() -> {
                TagEntity newEntity = entityAdapter.newEntity();
                newEntity.setName(definition.getName());
                newEntity.setFirstCreated(currentDate);
                return newEntity;
            });
            entity.setAttributes(new HashMap<>(definition.getAttributes()));
            entity.setComponents(new ArrayList<>(definition.getComponents()));
            entity.setLastUpdated(currentDate);

            if (existing.isPresent()) {
                entityAdapter.editEntity(tx, entity);
                log.info("Tag {} updated in database.", entity);
            } else {
                entityAdapter.addEntity(tx, entity);
                log.info("Tag {} added to database.", entity);
            }
            return entity.toDto();
        }
    }

    /**
     * @param name name of tag to delete
     * @throws TagNotFoundException if tag with given name does not exists
     */
    public void delete(String name) throws TagNotFoundException {
        log.info("Deleting {} tag.", name);
        try (ODatabaseDocumentTx tx = dbProvider.get().acquire().begin()) {
            TagEntity entity = getTag(name, tx);
            entityAdapter.deleteEntity(tx, entity);
            log.info("Tag {} deleted.", entity);
        }
    }

    /**
     * @param sourceTagName name of tag to clone
     * @param newTagName name of new tag
     * @param appendingAttributes attributes to append to cloned tag.
     * @return created tag
     * @throws TagNotFoundException if tag with given name does not exists
     * @throws TagAlreadyExistsException if tag with given new name already exists
     */
    public Tag cloneExisting(String sourceTagName, String newTagName, Map<String, String> appendingAttributes) {
        log.info("Cloning {} into {}, appending attributes:{}", sourceTagName, newTagName, appendingAttributes);
        try (ODatabaseDocumentTx tx = dbProvider.get().acquire().begin()) {
            TagEntity entity = getTag(sourceTagName, tx);
            if (entityAdapter.findByName(tx, newTagName).isPresent()) {
                throw new TagAlreadyExistsException();
            }
            TagEntity cloned = new TagEntity(entity);
            cloned.setName(newTagName);
            cloned.getAttributes().putAll(appendingAttributes);
            Date date = new Date();
            cloned.setFirstCreated(date);
            cloned.setLastUpdated(date);
            entityAdapter.addEntity(tx, cloned);
            log.info("Tag {} cloned into new tag: {}", sourceTagName, cloned);
            return cloned.toDto();
        }
    }

    private TagEntity getTag(String name, ODatabaseDocumentTx tx) {
        Optional<TagEntity> optional = entityAdapter.findByName(tx, name);
        if (!optional.isPresent()) {
            log.info("Tag {} not found.", name);
            throw new TagNotFoundException();
        }
        log.info("Tag {} found: {}", name, optional.get());
        return optional.get();
    }
}
