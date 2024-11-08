package fr.insee.onyxia.api.services.impl.kubernetes;

import fr.insee.onyxia.api.configuration.kubernetes.KubernetesClientProvider;
import fr.insee.onyxia.api.controller.exception.NamespaceNotFoundException;
import fr.insee.onyxia.api.events.InitNamespaceEvent;
import fr.insee.onyxia.api.events.OnyxiaEventPublisher;
import fr.insee.onyxia.api.services.impl.HelmAppsService;
import fr.insee.onyxia.model.User;
import fr.insee.onyxia.model.project.Project;
import fr.insee.onyxia.model.region.Region;
import fr.insee.onyxia.model.service.quota.Quota;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.rbac.RoleBinding;
import io.fabric8.kubernetes.api.model.rbac.RoleBindingBuilder;
import io.fabric8.kubernetes.api.model.rbac.SubjectBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.Resource;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class KubernetesService {

    private final KubernetesClientProvider kubernetesClientProvider;

    final OnyxiaEventPublisher onyxiaEventPublisher;
    private static final Logger LOGGER = LoggerFactory.getLogger(KubernetesService.class);
    public static final String ONYXIA_QUOTA = "onyxia-quota";

    @Autowired
    public KubernetesService(
            KubernetesClientProvider kubernetesClientProvider,
            OnyxiaEventPublisher onyxiaEventPublisher) {
        this.kubernetesClientProvider = kubernetesClientProvider;
        this.onyxiaEventPublisher = onyxiaEventPublisher;
    }

    public String createOrUpdateNamespace(Region region, Owner owner, User user) {
        final String namespaceId = getDefaultNamespace(region, owner);
        return createNamespace(region, namespaceId, owner, user);
    }

    @NotNull
    public String determineNamespaceAndCreateIfNeeded(Region region, Project project, User user) {
        if (region.getServices().isSingleNamespace()) {
            return getCurrentNamespace(region);
        }
        if (StringUtils.isEmpty(project.getNamespace())) {
            throw new NamespaceNotFoundException();
        }
        if (!isNamespaceAlreadyExisting(region, project.getNamespace())) {
            if (!region.getServices().isAllowNamespaceCreation()) {
                throw new NamespaceNotFoundException();
            } else {
                final KubernetesService.Owner owner = new KubernetesService.Owner();
                if (project.getGroup() != null) {
                    owner.setId(project.getGroup());
                    owner.setType(Owner.OwnerType.GROUP);
                } else {
                    owner.setId(user.getIdep());
                    owner.setType(KubernetesService.Owner.OwnerType.USER);
                }
                createNamespace(region, project.getNamespace(), owner, user);
            }
        }
        return project.getNamespace();
    }

    public String getCurrentNamespace(Region region) {
        return kubernetesClientProvider.getRootClient(region).getNamespace();
    }

    private String createNamespace(Region region, String namespaceId, Owner owner, User user) {
        final String name = getNameFromOwner(region, owner);

        final KubernetesClient kubClient = kubernetesClientProvider.getRootClient(region);

        Map<String, String> userMetadata = new HashMap<>();
        Region.Services.NamespaceAnnotationsDynamic namespaceAnnotationsDynamic =
                region.getServices().getNamespaceAnnotationsDynamic();
        if (namespaceAnnotationsDynamic.isEnabled() && user != null) {
            userMetadata.put(
                    "onyxia_last_login_timestamp", String.valueOf(System.currentTimeMillis()));
            for (String claim : namespaceAnnotationsDynamic.getUserAttributes()) {
                String claimValue = String.valueOf(user.getAttributes().getOrDefault(claim, ""));
                userMetadata.put("onyxia_" + claim, claimValue);
            }
        }

        Resource<Namespace> namespace =
                kubClient
                        .namespaces()
                        .resource(
                                new NamespaceBuilder()
                                        .withNewMetadata()
                                        .withName(namespaceId)
                                        .withLabels(region.getServices().getNamespaceLabels())
                                        .addToLabels("onyxia_owner", owner.getId())
                                        .withAnnotations(
                                                region.getServices().getNamespaceAnnotations())
                                        .addToAnnotations(userMetadata)
                                        .endMetadata()
                                        .build());
        boolean newNamespace = namespace.get() == null;
        namespace.serverSideApply();

        final RoleBinding bindingToCreate =
                kubClient
                        .rbac()
                        .roleBindings()
                        .inNamespace(namespaceId)
                        .createOrReplace(
                                new RoleBindingBuilder()
                                        .withNewMetadata()
                                        .withLabels(Map.of("createdby", "onyxia"))
                                        .withName("full_control_namespace")
                                        .withNamespace(namespaceId)
                                        .endMetadata()
                                        .withSubjects(
                                                new SubjectBuilder()
                                                        .withKind(getSubjectKind(owner))
                                                        .withName(name)
                                                        .withApiGroup("rbac.authorization.k8s.io")
                                                        .withNamespace(namespaceId)
                                                        .build())
                                        .withNewRoleRef()
                                        .withApiGroup("rbac.authorization.k8s.io")
                                        .withKind("ClusterRole")
                                        .withName("admin")
                                        .endRoleRef()
                                        .build());

        final boolean userEnabled =
                owner.getType() == Owner.OwnerType.USER
                        && region.getServices().getQuotas().isUserEnabled();
        final boolean groupEnabled =
                owner.getType() == Owner.OwnerType.GROUP
                        && region.getServices().getQuotas().isGroupEnabled();

        if (userEnabled) {
            Quota quota = region.getServices().getQuotas().getUserQuota();
            for (String role : user.getRoles()) {
                if (region.getServices().getQuotas().getRolesQuota().containsKey(role)) {
                    quota = region.getServices().getQuotas().getRolesQuota().get(role);
                    break; // take first role match
                }
            }
            LOGGER.info("applying user enabled style quota");
            applyQuotas(namespaceId, kubClient, quota, true);
        } else if (groupEnabled) {
            final Quota quota = region.getServices().getQuotas().getGroupQuota();
            LOGGER.info("applying group enabled style quota");
            applyQuotas(namespaceId, kubClient, quota, true);
        }
        if (newNamespace) {
            InitNamespaceEvent initNamespaceEvent =
                    new InitNamespaceEvent(region.getName(), namespaceId, owner.getId());
            onyxiaEventPublisher.publishEvent(initNamespaceEvent);
        }

        return namespaceId;
    }

    private boolean isNamespaceAlreadyExisting(Region region, String namespaceId) {
        return kubernetesClientProvider
                        .getRootClient(region)
                        .namespaces()
                        .withName(namespaceId)
                        .get()
                != null;
    }

    private void applyQuotas(
            String namespaceId,
            KubernetesClient kubClient,
            Quota inputQuota,
            boolean overrideExisting) {
        final ResourceQuotaBuilder resourceQuotaBuilder = new ResourceQuotaBuilder();
        resourceQuotaBuilder
                .withNewMetadata()
                .withLabels(Map.of("createdby", "onyxia"))
                .withName(ONYXIA_QUOTA)
                .withNamespace(namespaceId)
                .endMetadata();

        final Map<String, String> quotasToApply = inputQuota.asMap();

        if (quotasToApply.entrySet().stream().filter(e -> e.getValue() != null).count() == 0) {
            return;
        }

        var resourceQuotaBuilderSpecNested = resourceQuotaBuilder.withNewSpec();
        quotasToApply.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .forEach(
                        e ->
                                resourceQuotaBuilderSpecNested.addToHard(
                                        e.getKey(), Quantity.parse(e.getValue())));
        resourceQuotaBuilderSpecNested.endSpec();

        final ResourceQuota quota = resourceQuotaBuilder.build();
        ResourceQuota resourceQuota =
                kubClient.resourceQuotas().inNamespace(namespaceId).withName(ONYXIA_QUOTA).get();
        if (resourceQuota != null
                && resourceQuota.getMetadata().getAnnotations().containsKey("onyxia_ignore")) {
            // The annotation onyxia_ignore can be set to prevent Onyxia from managing this
            // resourcequota
            return;
        }
        if (overrideExisting) {
            kubClient.resourceQuotas().inNamespace(namespaceId).createOrReplace(quota);
        } else {
            try {
                kubClient.resourceQuotas().inNamespace(namespaceId).create(quota);
            } catch (final KubernetesClientException e) {
                if (e.getCode() != 409) {
                    // This is not a "quota already in place" error
                    throw e;
                }
            }
        }
    }

    public void createOnyxiaSecret(
            Region region, String namespaceId, String releaseName, Map<String, String> secretData) {

        final KubernetesClient kubClient = kubernetesClientProvider.getRootClient(region);
        String ownerSecretName =
                "sh.helm.release.v1." + releaseName + ".v1"; // Name of the Secret managed by Helm

        // Fetch the existing secret managed by Helm
        Secret ownerSecret =
                kubClient.secrets().inNamespace(namespaceId).withName(ownerSecretName).get();

        // Create owner reference
        OwnerReference ownerReference =
                new OwnerReferenceBuilder()
                        .withApiVersion(ownerSecret.getApiVersion())
                        .withKind(ownerSecret.getKind())
                        .withName(ownerSecret.getMetadata().getName())
                        .withUid(ownerSecret.getMetadata().getUid())
                        .withController(true) // Optional: Specifies that this owner is a controller
                        .build();

        // Define the new secret
        Secret newSecret =
                new SecretBuilder()
                        .withNewMetadata()
                        .withName(HelmAppsService.ONYXIA_SECRET_PREFIX + releaseName)
                        .addNewOwnerReferenceLike(ownerReference)
                        .endOwnerReference()
                        .endMetadata()
                        .addToData(secretData)
                        .withType("onyxia.sh/release.v1")
                        .build();

        // Create the secret in Kubernetes
        newSecret = kubClient.secrets().inNamespace(namespaceId).resource(newSecret).create();
    }

    private String getNameFromOwner(Region region, Owner owner) {
        String username = owner.getId();
        if (owner.getType() == Owner.OwnerType.USER
                && region.getServices().getUsernamePrefix() != null) {
            username = region.getServices().getUsernamePrefix() + username;
        } else if (owner.getType() == Owner.OwnerType.GROUP
                && region.getServices().getGroupPrefix() != null) {
            username = region.getServices().getGroupPrefix() + username;
        }
        return username;
    }

    private String getDefaultNamespace(Region region, Owner owner) {
        if (owner.getType() == Owner.OwnerType.USER) {
            return region.getServices().getNamespacePrefix() + owner.getId();
        } else {
            return region.getServices().getGroupNamespacePrefix() + owner.getId();
        }
    }

    public void applyQuota(Region region, Project project, User user, Quota quota) {
        final KubernetesClient kubClient = kubernetesClientProvider.getRootClient(region);
        final String namespace = determineNamespaceAndCreateIfNeeded(region, project, user);
        applyQuotas(namespace, kubClient, quota, true);
    }

    public ResourceQuota getOnyxiaQuota(Region region, Project project, User user) {
        final KubernetesClient kubClient = kubernetesClientProvider.getRootClient(region);
        final String namespace = determineNamespaceAndCreateIfNeeded(region, project, user);
        return kubClient.resourceQuotas().inNamespace(namespace).withName(ONYXIA_QUOTA).get();
    }

    private String getSubjectKind(Owner owner) {
        if (owner.getType() == Owner.OwnerType.GROUP) {
            return "Group";
        } else if (owner.getType() == Owner.OwnerType.USER) {
            return "User";
        }
        throw new IllegalArgumentException("Owner type must be one of : USER, GROUP");
    }

    public static class Owner {
        private String id;
        private OwnerType type;

        public static enum OwnerType {
            USER,
            GROUP;
        }

        public OwnerType getType() {
            return type;
        }

        public void setType(OwnerType type) {
            this.type = type;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }
    }
}
