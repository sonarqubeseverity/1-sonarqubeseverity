/*
 * SonarQube
 * Copyright (C) 2009-2018 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.ce.task.projectanalysis.component;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import org.sonar.ce.task.projectanalysis.analysis.Branch;
import org.sonar.core.util.stream.MoreCollectors;
import org.sonar.db.component.SnapshotDto;
import org.sonar.scanner.protocol.output.ScannerReport;
import org.sonar.scanner.protocol.output.ScannerReport.Component.FileStatus;
import org.sonar.server.project.Project;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.String.format;
import static org.apache.commons.lang.StringUtils.trimToNull;

public class ComponentTreeBuilder {

  private static final String DEFAULT_PROJECT_VERSION = "not provided";

  private final ComponentKeyGenerator keyGenerator;
  private final ComponentKeyGenerator publicKeyGenerator;
  /**
   * Will supply the UUID for any component in the tree, given it's key.
   * <p>
   * The String argument of the {@link Function#apply(Object)} method is the component's key.
   * </p>
   */
  private final Function<String, String> uuidSupplier;
  /**
   * Will supply the {@link ScannerReport.Component} of all the components in the component tree as we crawl it from the
   * root.
   * <p>
   * The Integer argument of the {@link Function#apply(Object)} method is the component's ref.
   * </p>
   */
  private final Function<Integer, ScannerReport.Component> scannerComponentSupplier;
  private final Project project;
  private final Branch branch;
  @Nullable
  private final SnapshotDto baseAnalysis;

  public ComponentTreeBuilder(
    ComponentKeyGenerator keyGenerator,
    ComponentKeyGenerator publicKeyGenerator,
    Function<String, String> uuidSupplier,
    Function<Integer, ScannerReport.Component> scannerComponentSupplier,
    Project project,
    Branch branch, @Nullable SnapshotDto baseAnalysis) {

    this.keyGenerator = keyGenerator;
    this.publicKeyGenerator = publicKeyGenerator;
    this.uuidSupplier = uuidSupplier;
    this.scannerComponentSupplier = scannerComponentSupplier;
    this.project = project;
    this.branch = branch;
    this.baseAnalysis = baseAnalysis;
  }

  public Component buildProject(ScannerReport.Component project, String scmBasePath) {
    return buildComponent(project, project, trimToNull(scmBasePath));
  }

  private List<Component> buildChildren(ScannerReport.Component component, ScannerReport.Component parentModule,
    String projectScmPath) {
    return component.getChildRefList()
      .stream()
      .map(scannerComponentSupplier::apply)
      .map(c -> buildComponent(c, parentModule, projectScmPath))
      .collect(MoreCollectors.toList(component.getChildRefCount()));
  }

  private ComponentImpl buildComponent(ScannerReport.Component component, ScannerReport.Component closestModule,
    @Nullable String scmBasePath) {
    switch (component.getType()) {
      case PROJECT:
        String projectKey = keyGenerator.generateKey(component, null);
        String uuid = uuidSupplier.apply(projectKey);
        String projectPublicKey = publicKeyGenerator.generateKey(component, null);
        ComponentImpl.Builder builder = ComponentImpl.builder(Component.Type.PROJECT)
          .setUuid(uuid)
          .setKey(projectKey)
          .setPublicKey(projectPublicKey)
          .setStatus(convertStatus(component.getStatus()))
          .setReportAttributes(createAttributesBuilder(component, scmBasePath)
            .setVersion(createProjectVersion(component))
            .build())
          .addChildren(buildChildren(component, component, scmBasePath));
        setNameAndDescription(component, builder);
        return builder.build();

      case MODULE:
        String moduleKey = keyGenerator.generateKey(component, null);
        String modulePublicKey = publicKeyGenerator.generateKey(component, null);
        return ComponentImpl.builder(Component.Type.MODULE)
          .setUuid(uuidSupplier.apply(moduleKey))
          .setKey(moduleKey)
          .setPublicKey(modulePublicKey)
          .setName(nameOfOthers(component, modulePublicKey))
          .setStatus(convertStatus(component.getStatus()))
          .setDescription(trimToNull(component.getDescription()))
          .setReportAttributes(createAttributesBuilder(component, scmBasePath).build())
          .addChildren(buildChildren(component, component, scmBasePath))
          .build();

      case DIRECTORY:
      case FILE:
        String key = keyGenerator.generateKey(closestModule, component);
        String publicKey = publicKeyGenerator.generateKey(closestModule, component);
        return ComponentImpl.builder(convertDirOrFileType(component.getType()))
          .setUuid(uuidSupplier.apply(key))
          .setKey(key)
          .setPublicKey(publicKey)
          .setName(nameOfOthers(component, publicKey))
          .setStatus(convertStatus(component.getStatus()))
          .setDescription(trimToNull(component.getDescription()))
          .setReportAttributes(createAttributesBuilder(component, scmBasePath).build())
          .setFileAttributes(createFileAttributes(component))
          .addChildren(buildChildren(component, closestModule, scmBasePath))
          .build();

      default:
        throw new IllegalArgumentException(format("Unsupported component type '%s'", component.getType()));
    }
  }

  public Component buildChangedComponentTreeRoot(Component project) {
    return buildChangedComponentTree(project);
  }

  private static ComponentImpl.Builder changedComponentBuilder(Component component) {
    return ComponentImpl.builder(component.getType())
      .setUuid(component.getUuid())
      .setKey(component.getKey())
      .setPublicKey(component.getPublicKey())
      .setStatus(component.getStatus())
      .setReportAttributes(component.getReportAttributes())
      .setName(component.getName())
      .setDescription(component.getDescription());
  }

  @Nullable
  private static Component buildChangedComponentTree(Component component) {
    switch (component.getType()) {
      case PROJECT:
        return buildChangedProject(component);

      case MODULE:
      case DIRECTORY:
        return buildChangedIntermediate(component);

      case FILE:
        return buildChangedFile(component);

      default:
        throw new IllegalArgumentException(format("Unsupported component type '%s'", component.getType()));
    }
  }

  private static Component buildChangedProject(Component component) {
    return changedComponentBuilder(component)
      .addChildren(buildChangedComponentChildren(component))
      .build();
  }

  @Nullable
  private static Component buildChangedIntermediate(Component component) {
    List<Component> children = buildChangedComponentChildren(component);
    if (children.isEmpty()) {
      return null;
    }
    return changedComponentBuilder(component)
      .addChildren(children)
      .build();
  }

  @Nullable
  private static Component buildChangedFile(Component component) {
    if (component.getStatus() == Component.Status.SAME) {
      return null;
    }
    return changedComponentBuilder(component)
      .setFileAttributes(component.getFileAttributes())
      .build();
  }

  private static List<Component> buildChangedComponentChildren(Component component) {
    return component.getChildren()
      .stream()
      .map(ComponentTreeBuilder::buildChangedComponentTree)
      .filter(Objects::nonNull)
      .collect(MoreCollectors.toList());
  }

  private void setNameAndDescription(ScannerReport.Component component, ComponentImpl.Builder builder) {
    if (branch.isMain()) {
      builder
        .setName(nameOfProject(component))
        .setDescription(component.getDescription());
    } else {
      builder
        .setName(project.getName())
        .setDescription(project.getDescription());
    }
  }

  private static Component.Status convertStatus(FileStatus status) {
    switch (status) {
      case ADDED:
        return Component.Status.ADDED;
      case SAME:
        return Component.Status.SAME;
      case CHANGED:
        return Component.Status.CHANGED;
      case UNAVAILABLE:
        return Component.Status.UNAVAILABLE;
      case UNRECOGNIZED:
      default:
        throw new IllegalArgumentException("Unsupported ComponentType value " + status);
    }
  }

  private String nameOfProject(ScannerReport.Component component) {
    String name = trimToNull(component.getName());
    if (name != null) {
      return name;
    }
    return project.getName();
  }

  private static String nameOfOthers(ScannerReport.Component reportComponent, String defaultName) {
    String name = trimToNull(reportComponent.getName());
    return name == null ? defaultName : name;
  }

  private String createProjectVersion(ScannerReport.Component component) {
    String version = trimToNull(component.getVersion());
    if (version != null) {
      return version;
    }
    if (baseAnalysis != null) {
      return firstNonNull(baseAnalysis.getVersion(), DEFAULT_PROJECT_VERSION);
    }
    return DEFAULT_PROJECT_VERSION;
  }

  private static ReportAttributes.Builder createAttributesBuilder(ScannerReport.Component component, @Nullable String scmBasePath) {
    return ReportAttributes.newBuilder(component.getRef())
      .setVersion(trimToNull(component.getVersion()))
      .setPath(trimToNull(component.getPath()))
      .setScmPath(computeScmPath(scmBasePath, component.getProjectRelativePath()));
  }

  @CheckForNull
  private static String computeScmPath(@Nullable String scmBasePath, String scmRelativePath) {
    if (scmRelativePath.isEmpty()) {
      return null;
    }
    if (scmBasePath == null) {
      return scmRelativePath;
    }
    return scmBasePath + '/' + scmRelativePath;
  }

  @CheckForNull
  private static FileAttributes createFileAttributes(ScannerReport.Component component) {
    if (component.getType() != ScannerReport.Component.ComponentType.FILE) {
      return null;
    }

    checkArgument(component.getLines() > 0, "File '%s' has no line", component.getPath());
    return new FileAttributes(
      component.getIsTest(),
      trimToNull(component.getLanguage()),
      component.getLines());
  }

  private static Component.Type convertDirOrFileType(ScannerReport.Component.ComponentType type) {
    switch (type) {
      case DIRECTORY:
        return Component.Type.DIRECTORY;
      case FILE:
        return Component.Type.FILE;
      default:
        throw new IllegalArgumentException("Unsupported ComponentType value " + type);
    }
  }
}
