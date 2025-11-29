package teammates.storage.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;

import teammates.common.datatransfer.attributes.AccountAttributes;
import teammates.common.datatransfer.attributes.CourseAttributes;
import teammates.common.datatransfer.attributes.InstructorAttributes;
import teammates.common.datatransfer.attributes.StudentAttributes;
import teammates.common.exception.SearchServiceException;
import teammates.storage.api.AccountsDb;
import teammates.storage.api.CoursesDb;
import teammates.storage.api.StudentsDb;

/**
 * Acts as a proxy to search service for student-related search features.
 */
public class StudentSearchManager extends SearchManager<StudentAttributes> {

    private final CoursesDb coursesDb = CoursesDb.inst();
    private final StudentsDb studentsDb = StudentsDb.inst();
    private final AccountsDb accountsDb = AccountsDb.inst();

    public StudentSearchManager(String searchServiceHost, boolean isResetAllowed) {
        super(searchServiceHost, isResetAllowed);
    }

    @Override
    String getCollectionName() {
        return "students";
    }

    @Override
    StudentSearchDocument createDocument(StudentAttributes student) {
        CourseAttributes course = coursesDb.getCourse(student.getCourse());
        AccountAttributes account = null;
        if (student.getGoogleId() != null && !student.getGoogleId().isEmpty()) {
            account = accountsDb.getAccount(student.getGoogleId());
        }
        return new StudentSearchDocument(student, course);
    }

    @Override
    StudentAttributes getAttributeFromDocument(SolrDocument document) {
        String courseId = (String) document.getFirstValue("courseId");
        String email = (String) document.getFirstValue("email");
        return studentsDb.getStudentForEmail(courseId, email);
    }

    @Override
    void sortResult(List<StudentAttributes> result) {
        result.sort(Comparator.comparing((StudentAttributes student) -> student.getCourse())
                .thenComparing(student -> student.getSection())
                .thenComparing(student -> student.getTeam())
                .thenComparing(student -> student.getName())
                .thenComparing(student -> student.getEmail()));
    }

    /**
     * Searches for students.
     *
     * @param instructors the constraint that restricts the search result
     */

    public List<StudentAttributes> searchStudents(String queryString, List<InstructorAttributes> instructors)
            throws SearchServiceException {

        String trimmed = queryString == null ? "" : queryString.trim();
        SolrQuery query;

        // Special handling for REGISTERED / UNREGISTERED
        if ("REGISTERED".equalsIgnoreCase(trimmed) || "UNREGISTERED".equalsIgnoreCase(trimmed)) {
            query = getBasicQuery("*:*");
            String status = trimmed.toUpperCase();
            query.addFilterQuery("isRegistered:\"" + status + "\"");
        } else {
            query = getBasicQuery(trimmed);
        }

        // === APPLY INSTRUCTOR VISIBILITY FILTER (this must come AFTER setting the base
        // query) ===
        // === APPLY INSTRUCTOR VISIBILITY FILTER ===
        List<String> visibleCourseIds = new ArrayList<>();
        if (instructors != null) {
            visibleCourseIds = instructors.stream()
                    .filter(i -> i.getPrivileges().getCourseLevelPrivileges().isCanViewStudentInSections())
                    .map(InstructorAttributes::getCourseId)
                    .distinct()
                    .collect(Collectors.toList());

            if (visibleCourseIds.isEmpty()) {
                return new ArrayList<>(); // instructor can't see any students
            }

            String courseFq = String.join("\" OR \"", visibleCourseIds);
            query.addFilterQuery("courseId:(\"" + courseFq + "\")");
        }
        QueryResponse response = performQuery(query);
        SolrDocumentList documents = response.getResults();

        // Final safety filter – use a final reference for lambda
        final List<String> finalVisibleCourseIds = visibleCourseIds;
        List<SolrDocument> filtered = documents.stream()
                .filter(doc -> instructors == null || finalVisibleCourseIds.contains(doc.getFirstValue("courseId")))
                .collect(Collectors.toList());

        return convertDocumentToAttributes(filtered);
    }

    private List<String> getCoursesWithViewStudentPrivilege(List<InstructorAttributes> instructors) {
        if (instructors == null) {
            return new ArrayList<>();
        }

        return instructors.stream()
                .filter(i -> i.getPrivileges().getCourseLevelPrivileges().isCanViewStudentInSections())
                .map(InstructorAttributes::getCourseId)
                .collect(Collectors.toList());
    }

    public List<StudentAttributes> searchStudents(
            String queryString,
            List<InstructorAttributes> instructors,
            String courseIdFilter,
            String sectionFilter,
            String teamFilter,
            String registrationFilter) throws SearchServiceException {

        SolrQuery query = getBasicQuery(queryString);

        // privilege-based course list
        List<String> courseIdsWithViewStudentPrivilege = getCoursesWithViewStudentPrivilege(instructors);

        if (instructors != null) {
            if (courseIdsWithViewStudentPrivilege.isEmpty()) {
                // instructor has no view-student privilege on any course
                return new ArrayList<>();
            }
            String courseIdFq = String.join("\" OR \"", courseIdsWithViewStudentPrivilege);
            query.addFilterQuery("courseId:(\"" + courseIdFq + "\")");
        }

        // NEW filters
        if (courseIdFilter != null && !courseIdFilter.isEmpty()) {
            query.addFilterQuery("courseId:\"" + courseIdFilter + "\"");
        }
        if (sectionFilter != null && !sectionFilter.isEmpty()) {
            query.addFilterQuery("section:\"" + sectionFilter + "\"");
        }
        if (teamFilter != null && !teamFilter.isEmpty()) {
            query.addFilterQuery("team:\"" + teamFilter + "\"");
        }
        /*
         * if (registrationFilter != null && !registrationFilter.isEmpty()) {
         * // Expect values like "REGISTERED" or "UNREGISTERED"
         * query.addFilterQuery("isRegistered:\"" + registrationFilter.toUpperCase() +
         * "\"");
         * }
         */
        if (registrationFilter != null && !registrationFilter.trim().isEmpty()) {
            String normalized = registrationFilter.trim().toUpperCase(); // REGISTERED / UNREGISTERED
            query.addFilterQuery("isRegistered:\"" + normalized + "\"");
        }

        QueryResponse response = performQuery(query);
        SolrDocumentList documents = response.getResults();

        // keep your sanity check / post-filter
        List<SolrDocument> filteredDocuments = documents.stream()
                .filter(document -> {
                    if (instructors == null) {
                        return true;
                    }
                    String courseId = (String) document.getFirstValue("courseId");
                    return courseIdsWithViewStudentPrivilege.contains(courseId);
                })
                .collect(Collectors.toList());

        return convertDocumentToAttributes(filteredDocuments);
    }

}
