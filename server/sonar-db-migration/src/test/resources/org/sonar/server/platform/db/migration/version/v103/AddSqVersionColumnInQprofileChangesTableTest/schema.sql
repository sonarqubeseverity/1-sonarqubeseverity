CREATE TABLE "QPROFILE_CHANGES"
(
    "KEE"                CHARACTER VARYING(40)  NOT NULL,
    "RULES_PROFILE_UUID" CHARACTER VARYING(255) NOT NULL,
    "CHANGE_TYPE"        CHARACTER VARYING(20)  NOT NULL,
    "USER_UUID"          CHARACTER VARYING(255),
    "CHANGE_DATA"        CHARACTER LARGE OBJECT,
    "CREATED_AT"         BIGINT                 NOT NULL,
    "RULE_CHANGE_UUID"   CHARACTER VARYING(40)
);
ALTER TABLE "QPROFILE_CHANGES"
    ADD CONSTRAINT "PK_QPROFILE_CHANGES" PRIMARY KEY ("KEE");
CREATE INDEX "QP_CHANGES_RULES_PROFILE_UUID" ON "QPROFILE_CHANGES" ("RULES_PROFILE_UUID" NULLS FIRST);
