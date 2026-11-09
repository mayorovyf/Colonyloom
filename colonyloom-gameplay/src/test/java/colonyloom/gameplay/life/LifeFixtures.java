package colonyloom.gameplay.life;

import colonyloom.core.life.content.LifeContent;
import colonyloom.core.life.content.LifeContent.Kind;
import colonyloom.core.life.content.LifeContent.Source;
import colonyloom.core.life.content.LifeIds;
import java.util.ArrayList;
import java.util.List;

public final class LifeFixtures {
    private LifeFixtures() {}

    public static Source source(Kind kind, String id, String json) { return new Source(kind, id, json); }

    public static List<Source> minimal() {
        List<Source> sources = new ArrayList<>();
        for (String id : LifeIds.teen_pro_YT_REQUIRED_THOUGHTS) {
            int mood = switch (id) {
                case LifeIds.teen_pro_YT_FRIEND_DIED, LifeIds.teen_pro_YT_FAMILY_DIED, LifeIds.teen_pro_YT_PARTNER_DIED, LifeIds.teen_pro_YT_COLONIST_DIED, LifeIds.teen_pro_YT_BROKE_UP,
                     LifeIds.teen_pro_YT_GOT_DUMPED, LifeIds.teen_pro_YT_GOT_SICK -> -8;
                default -> 6;
            };
            String opinion = id.equals(LifeIds.teen_pro_YT_GOT_DUMPED) ? ",\"opinion\":-20" : id.equals(LifeIds.teen_pro_YT_NEW_LOVER) ? ",\"opinion\":15" : "";
            sources.add(source(Kind.THOUGHTS, id, "{\"schemaVersion\":1,\"mood\":" + mood + ",\"durationDays\":1.5,\"stackLimit\":3" + opinion + "}"));
        }
        sources.add(source(Kind.THOUGHTS, "colonyloom:sick", "{\"schemaVersion\":1,\"mood\":-5,\"situation\":\"sick\"}"));
        sources.add(source(Kind.THOUGHTS, "colonyloom:pain_serious", "{\"schemaVersion\":1,\"mood\":-10,\"situation\":\"pain_serious\"}"));
        sources.add(source(Kind.THOUGHTS, "colonyloom:chitchat", "{\"schemaVersion\":1,\"mood\":2,\"durationDays\":0.5,\"opinion\":3,\"stackLimit\":5}"));
        sources.add(source(Kind.THOUGHTS, "colonyloom:insulted", "{\"schemaVersion\":1,\"mood\":-4,\"durationDays\":1,\"opinion\":-12,\"stackLimit\":3}"));
        sources.add(source(Kind.THOUGHTS, "colonyloom:comforted", "{\"schemaVersion\":1,\"mood\":5,\"durationDays\":1,\"opinion\":8}"));
        sources.add(source(Kind.THOUGHTS, "colonyloom:starving", "{\"schemaVersion\":1,\"mood\":-20,\"situation\":\"starving\"}"));
        sources.add(source(Kind.THOUGHTS, "colonyloom:rested", "{\"schemaVersion\":1,\"mood\":4,\"situation\":\"healthy_and_rested\"}"));

        sources.add(source(Kind.ACTIVITIES, "colonyloom:sleep", "{\"schemaVersion\":1,\"purpose\":\"sleep\",\"place\":\"bed\",\"pose\":\"lie\",\"schedule\":[\"sleep\"],\"gains\":{\"rest\":4},\"durationTicks\":[2000,8000],\"emote\":\"sleep\"}"));
        sources.add(source(Kind.ACTIVITIES, "colonyloom:bed_rest", "{\"schemaVersion\":1,\"purpose\":\"bed_rest\",\"place\":\"bed\",\"pose\":\"lie\",\"schedule\":[\"sleep\",\"work\",\"leisure\"],\"gains\":{\"rest\":2},\"durationTicks\":[1200,2400]}"));
        sources.add(source(Kind.ACTIVITIES, "colonyloom:collapse", "{\"schemaVersion\":1,\"purpose\":\"collapse\",\"place\":\"here\",\"pose\":\"lie\",\"schedule\":[\"sleep\",\"work\",\"leisure\"],\"durationTicks\":[400,800]}"));
        sources.add(source(Kind.ACTIVITIES, "colonyloom:idle", "{\"schemaVersion\":1,\"purpose\":\"idle\",\"place\":\"here\",\"animation\":\"look_around\",\"schedule\":[\"work\",\"leisure\"],\"durationTicks\":[100,300]}"));
        sources.add(source(Kind.ACTIVITIES, "colonyloom:sad_wander", "{\"schemaVersion\":1,\"purpose\":\"mental\",\"mental\":\"minor\",\"place\":\"wander\",\"schedule\":[\"sleep\",\"work\",\"leisure\"],\"memory\":\"colonyloom:catharsis\"}"));
        sources.add(source(Kind.ACTIVITIES, "colonyloom:child_play", "{\"schemaVersion\":1,\"purpose\":\"child\",\"tags\":[\"play\"],\"place\":\"wander\",\"animation\":\"jump\",\"ages\":[3,12],\"gains\":{\"recreation\":3}}"));
        sources.add(source(Kind.ACTIVITIES, "colonyloom:insulting_spree", "{\"schemaVersion\":1,\"purpose\":\"mental\",\"mental\":\"major\",\"tags\":[\"insult\"],\"place\":\"crowd\",\"schedule\":[\"sleep\",\"work\",\"leisure\"],\"emote\":\"angry\",\"durationTicks\":[600,1200]}"));
        sources.add(source(Kind.ACTIVITIES, "colonyloom:stay_with_parent", "{\"schemaVersion\":1,\"purpose\":\"child\",\"place\":\"parent\",\"ages\":[0,2],\"schedule\":[\"work\",\"leisure\"],\"gains\":{\"social\":2}}"));
        sources.add(source(Kind.ACTIVITIES, "colonyloom:chat_with_friend", "{\"schemaVersion\":1,\"purpose\":\"leisure\",\"tags\":[\"social\"],\"joy\":\"social\",\"place\":\"friend\",\"weight\":2,\"gains\":{\"recreation\":1.5,\"social\":3},\"speech\":3}"));
        sources.add(source(Kind.ACTIVITIES, "colonyloom:tend_patient", "{\"schemaVersion\":1,\"purpose\":\"care\",\"place\":\"here\",\"weight\":0,\"tags\":[\"care\",\"medical\"],\"allowSick\":true,\"schedule\":[\"sleep\",\"work\",\"leisure\"],\"durationTicks\":[200,400],\"animation\":\"nod\"}"));
        sources.add(source(Kind.ACTIVITIES, "colonyloom:stargaze", "{\"schemaVersion\":1,\"purpose\":\"leisure\",\"tags\":[\"calm\",\"outdoors\"],\"joy\":\"meditative\",\"place\":\"sky\",\"animation\":\"look_up\",\"time\":[13000,23000],\"weather\":\"clear\",\"gains\":{\"recreation\":2.5}}"));

        sources.add(source(Kind.NAMES, "colonyloom:en", "{\"schemaVersion\":1,\"male\":[\"Adam\",\"Boris\",\"Carl\",\"Dmitri\"],\"female\":[\"Anna\",\"Berta\",\"Clara\",\"Daria\"],\"family\":[\"Miller\",\"Smith\",[\"Ivanov\",\"Ivanova\"],\"Baker\"]}"));

        sources.add(source(Kind.DISEASES, LifeIds.teen_pro_YT_WOUND_INFECTION, "{\"schemaVersion\":1,\"category\":\"infectious\",\"pathogen\":\"bacterium\",\"severity\":\"severe\",\"routes\":[\"injury\"],\"systems\":[\"skin\",\"blood\"],\"symptoms\":[\"fever\",\"swelling\"],\"trigger\":{\"eventChance\":0.08,\"injury\":\"attack\"}}"));
        sources.add(source(Kind.DISEASES, LifeIds.teen_pro_YT_FOOD_POISONING, "{\"schemaVersion\":1,\"category\":\"infectious\",\"pathogen\":\"bacterium\",\"severity\":\"mild\",\"routes\":[\"food\"],\"systems\":[\"digestive\"],\"symptoms\":[\"vomit\",\"nausea\"],\"selfLimiting\":true,\"trigger\":{\"eventChance\":0.02}}"));
        sources.add(source(Kind.DISEASES, "colonyloom:common_cold", "{\"schemaVersion\":1,\"category\":\"infectious\",\"severity\":\"trivial\",\"systems\":[\"respiratory\"],\"symptoms\":[\"cough\",\"sneeze\",\"headache\"],\"contagiousness\":0.18,\"varieties\":{\"count\":6}}"));
        sources.add(source(Kind.DISEASES, "colonyloom:plague", "{\"schemaVersion\":1,\"category\":\"infectious\",\"pathogen\":\"bacterium\",\"severity\":\"deadly\",\"routes\":[\"contact\",\"airborne\",\"animal\"],\"systems\":[\"respiratory\",\"blood\"],\"symptoms\":[\"fever\",\"cough\",\"swelling\"],\"contagiousness\":0.12,\"varieties\":{\"count\":3}}"));
        sources.add(source(Kind.DISEASES, "colonyloom:hemophilia", "{\"schemaVersion\":1,\"category\":\"genetic\",\"severity\":\"moderate\",\"systems\":[\"blood\"],\"symptoms\":[\"bleeding\"]}"));
        sources.add(source(Kind.DISEASES, "colonyloom:impotence", "{\"schemaVersion\":1,\"category\":\"reproductive\",\"severity\":\"mild\",\"trigger\":{\"chancePerDay\":0.002,\"minAge\":30,\"sex\":\"male\"},\"subtypes\":[{\"id\":\"psychogenic\",\"trigger\":{\"chancePerDay\":0.001,\"minAge\":18,\"sex\":\"male\",\"moodBelow\":30},\"selfLimiting\":true,\"chronic\":false}]}"));

        sources.add(source(Kind.TRAITS, "colonyloom:straight", "{\"schemaVersion\":1,\"group\":\"orientation\",\"orientation\":\"straight\",\"weight\":8}"));
        sources.add(source(Kind.TRAITS, "colonyloom:gay", "{\"schemaVersion\":1,\"group\":\"orientation\",\"orientation\":\"gay\",\"weight\":1}"));
        sources.add(source(Kind.TRAITS, "colonyloom:kind", "{\"schemaVersion\":1,\"group\":\"temper\",\"effects\":{\"interactions\":{\"colonyloom:insult\":0.1}},\"conflicts\":[\"colonyloom:abrasive\"]}"));
        sources.add(source(Kind.TRAITS, "colonyloom:abrasive", "{\"schemaVersion\":1,\"group\":\"temper\",\"effects\":{\"interactions\":{\"colonyloom:insult\":4}},\"conflicts\":[\"colonyloom:kind\"]}"));
        sources.add(source(Kind.TRAITS, "colonyloom:night_owl", "{\"schemaVersion\":1,\"group\":\"chronotype\",\"scheduleShiftHours\":3}"));
        sources.add(source(Kind.TRAITS, "colonyloom:tough", "{\"schemaVersion\":1,\"group\":\"body\",\"effects\":{\"stats\":{\"pain_factor\":0.5}}}"));

        sources.add(source(Kind.GENES, "colonyloom:hemophilia_gene", "{\"schemaVersion\":1,\"xLinked\":true,\"alleles\":[{\"id\":\"normal\",\"frequency\":0.97,\"dominance\":2},{\"id\":\"hemophilia\",\"frequency\":0.03,\"dominance\":1}],\"condition\":{\"allele\":\"hemophilia\",\"zygosity\":\"homozygous\",\"disease\":\"colonyloom:hemophilia\"}}"));
        sources.add(source(Kind.GENES, "colonyloom:immunity", "{\"schemaVersion\":1,\"expression\":\"additive\",\"alleles\":[{\"id\":\"weak\",\"frequency\":0.25,\"effects\":{\"stats\":{\"immunity_gain\":0.8}}},{\"id\":\"average\",\"frequency\":0.5},{\"id\":\"strong\",\"frequency\":0.25,\"effects\":{\"stats\":{\"immunity_gain\":1.25}},\"traits\":{\"colonyloom:tough\":2}}]}"));

        sources.add(source(Kind.INTERACTIONS, "colonyloom:chat", "{\"schemaVersion\":1,\"kind\":\"chat\",\"weight\":5,\"onSuccess\":{\"initiator\":\"colonyloom:chitchat\",\"recipient\":\"colonyloom:chitchat\"},\"speech\":6,\"topics\":true}"));
        sources.add(source(Kind.INTERACTIONS, "colonyloom:insult", "{\"schemaVersion\":1,\"kind\":\"insult\",\"weight\":0.3,\"onSuccess\":{\"recipient\":\"colonyloom:insulted\"},\"emote\":\"angry\",\"speech\":4}"));
        sources.add(source(Kind.INTERACTIONS, "colonyloom:romance", "{\"schemaVersion\":1,\"kind\":\"romance\",\"weight\":0.6,\"requires\":{\"initiatorMinAge\":16,\"recipientMinAge\":16,\"relation\":\"single\",\"minOpinion\":20,\"romance\":true,\"notRelatives\":true},\"successChance\":0.5,\"onSuccess\":{\"initiator\":\"colonyloom:new_lover\",\"recipient\":\"colonyloom:new_lover\",\"change\":\"become_lovers\"},\"emote\":\"heart\",\"journal\":true}"));
        sources.add(source(Kind.INTERACTIONS, "colonyloom:proposal", "{\"schemaVersion\":1,\"kind\":\"proposal\",\"weight\":0.4,\"requires\":{\"relation\":\"lover\",\"minOpinion\":50},\"successChance\":0.9,\"onSuccess\":{\"change\":\"engage\"},\"onFailure\":{\"initiator\":\"colonyloom:insulted\"},\"emote\":\"heart\"}"));
        sources.add(source(Kind.INTERACTIONS, "colonyloom:vows", "{\"schemaVersion\":1,\"kind\":\"chat\",\"weight\":0.5,\"requires\":{\"relation\":\"fiance\"},\"onSuccess\":{\"change\":\"marry\"},\"emote\":\"heart\"}"));
        sources.add(source(Kind.INTERACTIONS, "colonyloom:breakup", "{\"schemaVersion\":1,\"kind\":\"breakup\",\"weight\":1,\"requires\":{\"relation\":\"partner\"},\"onSuccess\":{\"change\":\"break_up\"},\"emote\":\"sad\"}"));
        sources.add(source(Kind.INTERACTIONS, "colonyloom:comfort", "{\"schemaVersion\":1,\"kind\":\"comfort\",\"weight\":1,\"intimacy\":1.5,\"onSuccess\":{\"recipient\":\"colonyloom:comforted\"},\"speech\":3}"));
        return sources;
    }

    public static LifeContent content() { return LifeContent.load(minimal()); }
}
